package org.jenkinsci.harness;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.concurrent.Callable;
import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;


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

    public void startup() throws Exception {
        server = new Server(8080); // TODO bind only to localhost
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
        // TODO Failed to load class "org.slf4j.impl.StaticLoggerBinder".
        // Just load things from the test classpath which are in fact from Jetty, or from the Java platform.
        // Masker may need to load Hamcrest, etc which are used in testing but not part of jenkins
        ClassLoader masker = new ClassLoader(JenkinsMaskedClassesRunner.class.getClassLoader()) {
            final ClassLoader javaLoader = getParent().getParent();

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.matches("(org[.]eclipse[.]jetty|javax[.]servlet)[.].+")) {
                    return super.loadClass(name, resolve);
                } else {
                    return javaLoader.loadClass(name);
                }
            }

            @Override
            public URL getResource(String name) {
                if (name.matches("(org/eclipse/jetty|javax/servlet)/.+")) {
                    return super.getResource(name);
                } else {
                    return javaLoader.getResource(name);
                }
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.matches("(org/eclipse/jetty|javax/servlet)/.+")) {
                    return super.getResources(name);
                } else {
                    return javaLoader.getResources(name);
                }
            }
        };
        // Startup without polluting the webapp with test classes
        webapp.setClassLoader(new WebAppClassLoader(masker, webapp));
        System.setProperty("hudson.Main.development", "true");
        System.setProperty("jenkins.install.runSetupWizard", "false");
        server.start();
        Thread.sleep(15000); // TODO call WebAppMain.joinInit so it can handle long startups

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
            jenkinsClass.getMethod("cleanUp").invoke(jenkinsInstance); // TODO need to call getInstance and use the instance method
            uberClassLoader = null;
            server.stop();
            server.join();
            FileUtils.deleteDirectory(jenkinsHome);
            server = null;
            coreLoader = null;
            jenkinsClass = null;
            jenkinsInstance = null;
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
