package org.jenkinsci.test;

import com.cloudbees.workflow.flownode.FlowNodeUtil;
import com.cloudbees.workflow.rest.external.RunExt;
import com.google.common.cache.LoadingCache;
import hudson.PluginWrapper;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.Jenkins;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.jenkinsci.harness.BaseBenchmark;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.storage.SimpleXStreamFlowNodeStorage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.WarmupMode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class StageViewBenchmark extends BaseBenchmark  {
    Method benchmarkExecutionMethod;
    HttpClient client = new HttpClient();

    @Benchmark
    public Object stageViewBenchmark() throws Exception {
        // We can't cast the actualRunnable to anything that isn't in the main harness package due to custom classloading
        return benchmarkExecutionMethod.invoke(actualRunnable);
    }

    public Class getTestClass() {
        return StageViewBenchmark.class;
    }

    public boolean isSomethingHappening() {
        Jenkins jenkins = Jenkins.getInstance();
        if (!jenkins.getQueue().isEmpty())
            return true;
        for (Computer n : jenkins.getComputers())
            if (!n.isIdle())
                return true;
        return false;
    }

    public void waitUntilDone() {
        try {
            while(isSomethingHappening()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
    }

    private int readAndCount(InputStream strm) throws IOException {
        int count = 0;
        byte[] temp = new byte[8192];
        while (true) {
            int readout = strm.read(temp);
            if (readout > 0) {
                count += readout;
            } else {
                break;
            }
        }
        return count;
    }

    public Object benchmarkStageView() throws Exception {
        // Full HTTP request issuing
        GetMethod method = new GetMethod("http://localhost:8080/jenkins/job/benchmarkPipeline/wfapi/runs?fullStages=true");
        client.executeMethod(method);
        InputStream strm = new BufferedInputStream(method.getResponseBodyAsStream());
        return new Integer(readAndCount(strm));

        /*
        // Run just the pipeline analysis internals
        WorkflowRun run = Jenkins.getInstance().getItemByFullName("benchmarkPipeline", WorkflowJob.class).getLastBuild();
        RunExt ext = RunExt.createNew(run);
        return ext;
         */
    }

    @Setup(Level.Trial)
    public void setupIterationInvoker() {
        try{
            this.actualRunnable.getClass().getMethod("setupIteration").invoke(this.actualRunnable);
        } catch (NoSuchMethodException|InvocationTargetException|IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void setupIteration() {
        this.client = new HttpClient();
    }

    public void setup() throws Exception {
        super.setup();
        this.actualRunnable.getClass().getMethod("printVersions").invoke(actualRunnable);
        benchmarkExecutionMethod = actualRunnable.getClass().getMethod("benchmarkStageView");
    }

    public void flushFlowNodeCache(WorkflowJob job) {
        try{
            Field nodeCacheField = SimpleXStreamFlowNodeStorage.class.getDeclaredField("nodeCache");
            nodeCacheField.setAccessible(true);
            for (WorkflowRun run : job.getBuilds()) {
                SimpleXStreamFlowNodeStorage storage = (SimpleXStreamFlowNodeStorage)(((CpsFlowExecution)(run.getExecution())).getStorage());
                LoadingCache myCache = (LoadingCache)(nodeCacheField.get(storage));
                myCache.invalidateAll();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        FlowNodeUtil.CacheExtension.all().get(0).getRunCache().invalidateAll();
    }

    @Override
    public void setupInvocation() {
        try{
            Jenkins jenkins = Jenkins.getInstance();
            deleteProjects();
            WorkflowJob p = jenkins.createProject(WorkflowJob.class, "benchmarkPipeline");
            p.setDefinition(new CpsFlowDefinition("" +
                    "for (int i=0; i<15; i++) {\n" +
                    "    stage \"stage $i\" \n" +
                    "    echo \"ran my stage is $i\"        \n" +
                    "    node {\n" +
                    "        echo 'whoami';\n" +
                    "    }\n" +
                    "}\n" +
                    "node {sh'whoami';} \n"+
                    "\n" +
                    "stage 'label based'\n" +
                    "echo 'wait for executor'\n" +
                    "node {\n" +
                    "    stage 'things using node'\n" +
                    "    for (int i=0; i<200; i++) {\n" +
                    "        echo \"we waited for this $i seconds\"    \n" +
                    "    }\n" +
                    "}", true));
            for (int i=0; i<10; i++) {  // These will run in parallel
                WorkflowRun run = p.scheduleBuild2(0).get();
                while (run.getExecution() == null || !run.getExecution().isComplete()) {
                    Thread.sleep(50);
                }
            }

            WorkflowRun run = p.getLastBuild();
            System.out.println("Iota: "+((CpsFlowExecution)run.getExecution()).iota());
            flushFlowNodeCache(p);
            System.gc(); //For a very good reason: weak reference caches
        } catch (Exception ex) {
            ex.printStackTrace();
            try {
                this.teardown();
            } catch (Exception ex2) {
                ex2.printStackTrace();
                System.exit(1);
            }
            throw new RuntimeException("Failed on setup invocation", ex);
        }
    }

    public void deleteProjects() {
        try {
            Jenkins j = Jenkins.getInstance();
            Item it = j.getItemByFullName("benchmarkPipeline");
            if (it != null) {
                it.delete();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Print jenkins & plugin names and versions */
    public void printVersions() {
        System.out.println("Jenkins version: "+Jenkins.getVersion().toString());
        List<PluginWrapper> plugins = Jenkins.getInstance().getPluginManager().getPlugins();
        Collections.sort(plugins);
        for(PluginWrapper pw : plugins) {
            System.out.println(pw.getShortName()+":"+pw.getVersion());
        }

    }

    public void teardown() throws Exception {
        this.actualRunnable.getClass().getMethod("printVersions").invoke(actualRunnable);
        super.teardown();
    }

    @Override
    public void tearDownInvocation() {
        deleteProjects();
    }

    public static void main(String[] args) throws Exception {
        //Test the basic code
        /*StageViewBenchmark bench = new StageViewBenchmark();
        try {
            bench.setup();
            bench.setupInvocationInvoker();
            bench.stageViewBenchmark();
            bench.tearDownInvocationInvoker();
            bench.teardown();
        } catch (Exception ex) {
            try {
                ex.printStackTrace();
                bench.teardown(); // Clean shutdown
            } catch (Exception ex2) {
                ex2.printStackTrace();
                System.exit(1); // Fall back to dirty shutdown
            }
        }*/

        Options opt = new OptionsBuilder()
                // Specify which benchmarks to run.
                // You can be more specific if you'd like to run only one benchmark per test.
                .include(StageViewBenchmark.class.getName() + ".*")
                // Set the following options as needed
                .mode (Mode.AverageTime)
                .timeUnit(TimeUnit.MILLISECONDS)
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(300))
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(60))
                .threads(1)
                .forks(1)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();
        try {
            new Runner(opt).run();
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(0);
        }
    }
}
