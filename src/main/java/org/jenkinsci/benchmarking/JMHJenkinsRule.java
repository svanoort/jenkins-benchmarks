package org.jenkinsci.benchmarking;


import hudson.ClassicPluginStrategy;
import hudson.DNSMultiCast;
import hudson.FilePath;
import hudson.Functions;
import hudson.PluginWrapper;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.util.jna.GNUCLibrary;
import jenkins.model.Jenkins;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.jvnet.hudson.test.ThreadPoolImpl;
import org.kohsuke.stapler.StaplerRequest;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;


/**
 * Analogous to JenkinsRule but i
 */
@State(Scope.Benchmark)
public class JMHJenkinsRule {

    private static final Logger LOGGER = Logger.getLogger(JMHJenkinsRule.class.getName());
    File jenkinsHome;
    Jenkins jenkinsInstance;
    int jenkinsPort = -1;
    String contextPath = "/jenkins";
    protected Server server;
    protected ClassLoader uberClassLoader;


    public static final MimeTypes MIME_TYPES = new MimeTypes();
    static {
        MIME_TYPES.addMimeMapping("js","application/javascript");
        Functions.DEBUG_YUI = true;

        // during the unit test, predictably releasing classloader is important to avoid
        // file descriptor leak.
        ClassicPluginStrategy.useAntClassLoader = true;

        // DNS multicast support takes up a lot of time during tests, so just disable it altogether
        // this also prevents tests from falsely advertising Hudson
        DNSMultiCast.disabled = true;

        try {
            GNUCLibrary.LIBC.unsetenv("MAVEN_OPTS");
            GNUCLibrary.LIBC.unsetenv("MAVEN_DEBUG_OPTS");
        } catch (LinkageError x) {
            // skip; TODO 1.630+ can use Functions.isGlibcSupported
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING,"Failed to cancel out MAVEN_OPTS",e);
        }
    }


    public File createJenkinsHome() {
        File base = new File(System.getProperty("java.io.tmpdir"), "jenkinsTests.tmp");
        base.delete();
        base.mkdirs();
        return base;
    }

    public Jenkins createJenkins(File homedir) {
        // Bypass 2.x security
        System.setProperty("hudson.Main.development", "true");
        System.setProperty("jenkins.install.runSetupWizard", "false");
        try {
            ServletContext webServer = createWebServer();
            return new Hudson(homedir, webServer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void installPluginFromLocal() {
        // Use https://github.com/jenkinsci/parallel-test-executor-plugin/pull/20/files
        // Or something gnarly with local fetch
    }

    public void installPlugins(Jenkins j, List<String> shortPluginNames) throws Exception {
        if (shortPluginNames.size() > 0) {
            List<Future<UpdateCenter.UpdateCenterJob>> futures = new ArrayList<Future<UpdateCenter.UpdateCenterJob>>();
            for (String pluginName : shortPluginNames) {
                UpdateSite.Plugin p = j.getUpdateCenter().getPlugin(pluginName);
                Future<UpdateCenter.UpdateCenterJob> fut = p.deploy(true);
                futures.add(fut);
            }
            for (Future<UpdateCenter.UpdateCenterJob> fut : futures) {
                if (fut.get().getError() != null) {
                    throw new RunnerException(fut.get().getError());
                }
            }
        }
    }

    /**
     * Configures a security realm for a test.
     */
    protected LoginService configureUserRealm() {
        HashLoginService realm = new HashLoginService();
        realm.setName("default");   // this is the magic realm name to make it effective on everywhere
        realm.update("alice", new Password("alice"), new String[]{"user","female"});
        realm.update("bob", new Password("bob"), new String[]{"user","male"});
        realm.update("charlie", new Password("charlie"), new String[]{"user","male"});

        return realm;
    }

    /**
     * Prepares a webapp hosting environment to get {@link javax.servlet.ServletContext} implementation
     * that we need for testing.  Based on JenkinsRule
     */
    protected ServletContext createWebServer() throws Exception {
        server = new Server(new ThreadPoolImpl(new ThreadPoolExecutor(10, 10, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("Jetty Thread Pool");
                return t;
            }
        })));

        WebAppContext context = new WebAppContext(WarExploder.getExplodedDir().getPath(), contextPath);
        context.setClassLoader(getClass().getClassLoader());
        context.setConfigurations(new Configuration[]{new WebXmlConfiguration()});
        context.addBean(new NoListenerConfiguration(context));
        server.setHandler(context);
        context.setMimeTypes(MIME_TYPES);
        context.setResourceBase(WarExploder.getExplodedDir().getPath());
        context.getSecurityHandler().setLoginService(configureUserRealm());

        ServerConnector connector = new ServerConnector(server);
        HttpConfiguration config = connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        // use a bigger buffer as Stapler traces can get pretty large on deeply nested URL
        config.setRequestHeaderSize(12 * 1024);
        connector.setHost("localhost");
        if (System.getProperty("port")!=null)
            connector.setPort(Integer.parseInt(System.getProperty("port")));

        server.addConnector(connector);
        server.start();

        jenkinsPort = connector.getLocalPort();
        LOGGER.log(java.util.logging.Level.INFO, "Running on {0}", getURL());

        return context.getServletContext();
    }

    // Need to hook the test code into Uberclassloader to be able to
    // access plugin code
    @Setup(Level.Trial)
    public void setup() throws Exception {
        File homeDir = createJenkinsHome();
        jenkinsInstance = createJenkins(homeDir);
        List<String> plugins = Collections.singletonList("workflow-aggregator");
        uberClassLoader = jenkinsInstance.getPluginManager().uberClassLoader;
        installPlugins(jenkinsInstance, plugins);
    }

    /**
     * Returns the URL of the webapp top page.
     * URL ends with '/'.
     */
    public URL getURL() throws IOException {
        return new URL("http://localhost:"+jenkinsPort+contextPath+"/");
    }


    public void shutdownJenkins(Jenkins j) {
        j.cleanUp(); // Enters a state where we can kill the thread & dir, without nuking the VM process
    }

    @Benchmark
    public Job createJob() throws IOException, InterruptedException {
        FreeStyleProject fp = jenkinsInstance.createProject(FreeStyleProject.class, "MyNewProject");
        fp.delete();
        return fp;
    }

    @Benchmark
    public int pipelineBenchmark() {
        //return -1;
    }

    @TearDown(Level.Trial)
    public void teardown() {
        shutdownJenkins(jenkinsInstance);
        jenkinsInstance = null;
        try {
            if (jenkinsHome != null && jenkinsHome.exists()) {
                new FilePath(jenkinsHome).deleteRecursive();
            }
        } catch (InterruptedException|IOException ie) {
            throw new RuntimeException(ie);
        }
        jenkinsHome = null;
    }

    public static void main(String[] args) throws Exception {

        Options opt = new OptionsBuilder()
                // Specify which benchmarks to run.
                // You can be more specific if you'd like to run only one benchmark per test.
                .include(JMHJenkinsRule.class.getName() + ".*")
                // Set the following options as needed
                .mode (Mode.AverageTime)
                .timeUnit(TimeUnit.MICROSECONDS)
                .warmupTime(TimeValue.seconds(30))
                .warmupIterations(1)
                .measurementTime(TimeValue.seconds(10))
                .measurementIterations(3)
                .threads(1)
                .forks(2)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();
        new Runner(opt).run();
    }

}
