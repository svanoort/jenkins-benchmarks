package org.jenkinsci.test;

import hudson.model.FreeStyleProject;
import java.util.concurrent.TimeUnit;

import hudson.model.Item;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkinsci.harness.SingletonBenchmark;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

public class Main extends SingletonBenchmark<Run>  {

    public Class getTestClass() {
        return Main.class;
    }

    @Override public Run call() throws Exception {
        WorkflowJob p = Jenkins.getInstance().createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        WorkflowRun b = p.scheduleBuild2(0).get();
        System.out.println(Jenkins.getInstance().getPluginManager().getPlugins());
        FreeStyleProject p2 = Jenkins.getInstance().createProject(FreeStyleProject.class, "p2");
        p2.scheduleBuild2(0).get();
        return p.getLastBuild();
    }

    @Override
    public void setupInvocation() {
        deleteProjects();
    }

    public void deleteProjects() {
        try {
            Jenkins j = Jenkins.getInstance();
            Item it = j.getItemByFullName("p");
            if (it != null) {
                it.delete();
            }
            it = j.getItemByFullName("p2");
            if (it != null) {
                it.delete();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void tearDownInvocation() {
        deleteProjects();
    }

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
                .warmupTime(TimeValue.seconds(300))
                .warmupIterations(1)
                .measurementTime(TimeValue.seconds(120))
                .measurementIterations(3)
                .threads(1)
                .forks(1)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .build();
        try {
            new Runner(opt).run();
        } catch (Exception ex) {
            System.exit(0);
        } finally {
            System.exit(0);
        }
    }
}
