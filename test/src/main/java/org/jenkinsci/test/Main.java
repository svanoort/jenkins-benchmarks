package org.jenkinsci.test;

import hudson.model.FreeStyleProject;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkinsci.harness.JenkinsMaskedClassesRunner;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
public class Main implements Callable<Run> {
    JenkinsMaskedClassesRunner maskedClassesRunner = new JenkinsMaskedClassesRunner();
    Callable<Run> actualRunnable;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        maskedClassesRunner.startup();
        actualRunnable = maskedClassesRunner.createTestClass(this.getClass());
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        maskedClassesRunner.shutdown();
    }

    @Benchmark
    public Object benchmark() throws Exception {
        return actualRunnable.call();
    }

    @Override public Run call() throws Exception {
        Jenkins j = Jenkins.getInstance();
        Item it = j.getItemByFullName("p");
        if (it != null) {
            it.delete();
        }
        it = j.getItemByFullName("p2");
        if (it != null) {
            it.delete();
        }

        WorkflowJob p = Jenkins.getInstance().createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        WorkflowRun b = p.scheduleBuild2(0).get();
        System.out.println(Jenkins.getInstance().getPluginManager().getPlugins());
        FreeStyleProject p2 = Jenkins.getInstance().createProject(FreeStyleProject.class, "p2");
        p2.scheduleBuild2(0).get();
        return p.getLastBuild();
    }

    /*@Setup(Level.Iteration)
    @TearDown(Level.Iteration)
    public void classSafeTeardown() throws Exception {
        // Has to be this way because custom classloading: otherwise we get exception casting hudson to itself
        // This is because they were loaded from different classloaders
        // Also fun point: actualRunnable is type Main_jmh and not Main, so can't cast to Main
        actualRunnable.getClass().getMethod("deleteJobs").invoke(actualRunnable);
    }*/


    public static void main(String[] args) throws Exception {
        /*JenkinsMaskedClassesRunner runner = new JenkinsMaskedClassesRunner();
        runner.runSingle(Main.class);*/
        Options opt = new OptionsBuilder()
                // Specify which benchmarks to run.
                // You can be more specific if you'd like to run only one benchmark per test.
                .include(Main.class.getName() + ".*")
                // Set the following options as needed
                .mode (Mode.AverageTime)
                .timeUnit(TimeUnit.MICROSECONDS)
                .warmupTime(TimeValue.seconds(30))
                .warmupIterations(1)
                .measurementTime(TimeValue.seconds(30))
                .measurementIterations(3)
                .threads(1)
                .forks(1)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();
        new Runner(opt).run();
    }
}
