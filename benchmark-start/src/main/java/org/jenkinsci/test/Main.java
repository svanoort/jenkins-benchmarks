package org.jenkinsci.test;

import hudson.model.FreeStyleProject;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import jenkins.model.Jenkins;
import org.jenkinsci.harness.JenkinsMaskedClassesRunner;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;


// TODO find out if I can play nicely with JMH?
public class Main implements Callable<Void> {
    @Override public Void call() throws Exception {
        WorkflowJob p = Jenkins.getInstance().createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        WorkflowRun b = p.scheduleBuild2(0).get();
        System.out.println(Jenkins.getInstance().getPluginManager().getPlugins());
        FreeStyleProject p2 = Jenkins.getInstance().createProject(FreeStyleProject.class, "p2");
        p2.scheduleBuild2(0).get();
        return null;
    }

    @Benchmark
    public int doSum() {
        return (int)(Math.random() + Math.random());
    }

    public static void main(String[] args) throws Exception {

        Options opt = new OptionsBuilder()
                // Specify which benchmarks to run.
                // You can be more specific if you'd like to run only one benchmark per test.
                .include(Main.class.getName() + ".*")
                // Set the following options as needed
                .mode (Mode.AverageTime)
                .timeUnit(TimeUnit.MICROSECONDS)
                .warmupTime(TimeValue.seconds(5))
                .warmupIterations(1)
                .measurementTime(TimeValue.seconds(5))
                .measurementIterations(3)
                .threads(1)
                .forks(1)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();
        new Runner(opt).run();

//        JenkinsMaskedClassesRunner.run(Main.class);
    }
}
