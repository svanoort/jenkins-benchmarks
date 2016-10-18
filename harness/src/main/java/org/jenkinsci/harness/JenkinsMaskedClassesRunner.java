package org.jenkinsci.harness;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.concurrent.Callable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;


// Runs Jenkins in an isolated classLoader: note we can't call any Jenkins methods here
// Those all have to be in the testcase main (
public class JenkinsMaskedClassesRunner {
    Class<?> jenkinsClass = null;
    Object jenkinsInstance = null;
    ClassLoader coreLoader = null;
    ClassLoader uberClassLoader = null;
    ClassLoader testLoader = null;
    Server server = null;
    File jenkinsHome = null;

    public Object getJenkins() {
        return jenkinsInstance;
    }

    public ClassLoader getTestLoader() {
        return testLoader;
    }

    public void startup() throws Exception {
        server = new Server(new InetSocketAddress("127.0.0.1", 8080));  // Security: bind only to connections from localhost
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/jenkins");
        jenkinsHome = File.createTempFile("jenkinsHome", ".tmp");
        jenkinsHome.delete();
        File plugins = new File(jenkinsHome, "plugins");
        plugins.mkdirs();
        jenkinsHome.deleteOnExit();

        // Set up a WAR and plugin path to drop into our home for startup
        for (String elt : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (elt.endsWith(".jar")) {
                File f = new File(elt.replaceFirst("[.]jar$", ".hpi"));  // Works b/c we downloaded the HPI files too
                if (f.isFile()) {
                    File jpi = new File(plugins, f.getName().replace(".hpi", ".jpi")); // TODO strip out version
                    Files.copy(f.toPath(), jpi.toPath());
                    System.out.println("created " + jpi);
                } else if (elt.endsWith("-war-for-test.jar")) {
                    webapp.setWar(elt);
                    System.out.println("loading from " + elt);
                } else {
                    System.out.println("ignoring " + elt);
                }
            }
        }
        server.setHandler(webapp);


        System.setProperty("JENKINS_HOME", jenkinsHome.getAbsolutePath());
        HashLoginService realm = new HashLoginService();
        realm.setName("default");
        //        realm.update("alice", new Password("alice"), new String[]{"user","female"});
        webapp.getSecurityHandler().setLoginService(realm);
        // Just load things from the test classpath which are in fact from Jetty, or from the Java platform.
        // Masker may need to load Hamcrest, etc which are used in testing but not part of jenkins
        ClassLoader masker = new ClassLoader(JenkinsMaskedClassesRunner.class.getClassLoader()) {
            final ClassLoader javaLoader = getParent().getParent();

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.matches("(org[.]eclipse[.]jetty|javax[.]servlet|org[.]jenkinsci[.]harness|org[.]openjdk[.]jmh)[.].+")) {
                    return super.loadClass(name, resolve);
                } else {
                    return javaLoader.loadClass(name);
                }
            }

            @Override
            public URL getResource(String name) {
                if (name.matches("(org/eclipse/jetty|javax/servlet|org/jenkinsci/harness|org/openjdk/jmh)/.+")) {
                    return super.getResource(name);
                } else {
                    return javaLoader.getResource(name);
                }
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.matches("(org/eclipse/jetty|javax/servlet|org/jenkinsci/harness|org/openjdk/jmh)/.+")) {
                    return super.getResources(name);
                } else {
                    return javaLoader.getResources(name);
                }
            }
        };
        // Startup without polluting the webapp with test classes
        webapp.setClassLoader(new WebAppClassLoader(masker, webapp));

        // Startup fixes & optimizations, goes from 19 seconds from doStart to 15 sec (just thread sleep)
        System.setProperty("hudson.Main.development", "true");
        System.setProperty("hudson.model.UpdateCenter.never", "true"); // Checking for updates is slow & not needed when we prepopulate plugins
        System.setProperty("hudson.model.DownloadService.never", "true"); // No need to download periodically
        // TODO Find a way to preload the required plugin data files, since it does do the initial fetch and is very hard to bypass
        System.setProperty("hudson.DNSMultiCast.disabled", "true"); // Claimed to be slow
        System.setProperty("jenkins.install.runSetupWizard", "false"); // Disable Jenkins 2 setup wizard
        System.setProperty("hudson.udp", "-1");  // Not needed
        System.setProperty("hudson.model.UsageStatistics.disabled", "true");

        server.start();

        // Find the initialization thread from the Jenkins WebAppMain and wait for it to finish before proceeding
        // A bit hacky, but you need to be able to obtain the actual WebAppMain instance to call joinInit on it
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().contains("Jenkins initialization thread")) {
                System.out.println("Joining on: "+t.getName());
                try {
                    long start = System.currentTimeMillis();
                    t.join(30000);
                    long end = System.currentTimeMillis();
                    System.out.println("Jenkins internals started in "+(end-start)+ " ms");
                } catch (InterruptedException ie) {
                    System.out.println("Jenkins failed to initialize within timeout, aborting!");
                    try {
                        shutdown();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        System.out.println("Jenkins failed to shutdown cleanly, taking the nuclear option and exiting the JVM!");
                        System.exit(1);
                    }
                }
                break;
            }
        }

        // Gets the classloader for jenkins itself without plugins
        coreLoader = webapp.getClassLoader();
        jenkinsClass = coreLoader.loadClass("jenkins.model.Jenkins");
        jenkinsInstance = jenkinsClass.getMethod("getInstance").invoke(null);
        Object pluginManager = jenkinsClass.getMethod("getPluginManager").invoke(jenkinsInstance);

        // New we can use the uberclassloader which sees all the jenkins plugins too
        uberClassLoader = (ClassLoader) pluginManager.getClass().getField("uberClassLoader").get(pluginManager);
        testLoader = new URLClassLoader(((URLClassLoader)JenkinsMaskedClassesRunner.class.getClassLoader()).getURLs(), uberClassLoader);
    }

    public void shutdown() {
        try {
            if (jenkinsInstance == null) {
                jenkinsInstance = jenkinsClass.getMethod("getInstance").invoke(null);
            }
            jenkinsClass.getMethod("cleanUp").invoke(jenkinsInstance);
            uberClassLoader = null;
            server.stop();
            server.join();
            FileUtils.deleteDirectory(jenkinsHome);
            server = null;
            coreLoader = null;
            jenkinsClass = null;
            jenkinsInstance = null;
            testLoader = null;
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }

    }

    public Callable createTestClass(Class<? extends Callable> testClass) throws Exception {
        if (testLoader == null) {
            startup();
        }
        Callable<Void> main = (Callable) testLoader.loadClass(testClass.getName()).newInstance();
        if (main.getClass().getClassLoader() != testLoader) {
            throw new IllegalStateException("wrong loader");
        }
        return main;
    }

    /*
    // Install/updates plugins from update center, saved for future use if needed.
    public void updatePluginsFromUpdateCenter(List<String> shortPluginNames) throws Exception {
        PluginManager pm = jenkinsInstance.getPluginManager();
        if (shortPluginNames.size() > 0) {
            UpdateCenter up = jenkinsInstance.getUpdateCenter();
            if (up.getAvailables().size() == 0) {
                up.updateAllSites();
            }
            List<Future<UpdateCenter.UpdateCenterJob>> futures = new ArrayList<Future<UpdateCenter.UpdateCenterJob>>();
            for (String pluginName : shortPluginNames) {
                UpdateSite.Plugin plug = jenkinsInstance.getUpdateCenter().getPlugin(pluginName);
                if (plug.isNewerThan(pm.getPlugin(pluginName).getVersion())) {
                    plug.deploy(true);
                }
                UpdateSite.Plugin p = jenkinsInstance.getUpdateCenter().getPlugin(pluginName);
                Future<UpdateCenter.UpdateCenterJob> fut = p.deploy(true);
                futures.add(fut);
            }
            // Deployment errors, boo!
            for (Future<UpdateCenter.UpdateCenterJob> fut : futures) {
                if (fut.get().getError() != null) {
                    throw new RunnerException(fut.get().getError());
                }
            }
        }
    }
     */

    public void runSingle(Class<? extends Callable<?>> callableClass) throws Exception {
        try {
            startup();
            try {
                @SuppressWarnings("unchecked")
                Callable<Void> main = createTestClass(callableClass);
                main.call();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            shutdown();
        }
    }

}
