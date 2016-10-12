package org.jenkinsci.test;

import hudson.model.Item;
import hudson.model.Result;
import jenkins.model.Jenkins;
import org.jenkinsci.harness.BaseBenchmark;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

public class PipelineBenchmark extends BaseBenchmark  {
    Method benchmarkExecutionMethod;

    @Benchmark
    public Object runPipelineBenchmark() throws Exception {
        // We can't cast the actualRunnable to anything that isn't in the main harness package due to custom classloading
        return benchmarkExecutionMethod.invoke(actualRunnable);
    }

    public Class getTestClass() {
        return PipelineBenchmark.class;
    }

    public FlowExecution runPipeline() throws Exception {
        WorkflowJob p = Jenkins.getInstance().getItemByFullName("benchmarkPipeline", WorkflowJob.class);
        WorkflowRun run = p.scheduleBuild2(0).get();
        if (run.isBuilding()) {
            System.out.println("Build still running!");
        }
        if (run.getResult() != Result.SUCCESS) {
            System.out.println("Non-success build: "+run.getResult());
        }
        return run.getExecution();
    }

    public void setup() throws Exception {
        super.setup();
        benchmarkExecutionMethod = actualRunnable.getClass().getMethod("benchmarkStageView");
    }

    @Override
    public void setupInvocation() {
        try{
            deleteProjects();
            WorkflowJob p = Jenkins.getInstance().createProject(WorkflowJob.class, "benchmarkPipeline");
            p.setDefinition(new CpsFlowDefinition("" +
                    "for (int i=0; i<15; i++) {\n" +
                    "    stage \"stage $i\" \n" +
                    "    echo \"ran my stage is $i\"        \n" +
                    "    node {\n" +
                    "        sh 'whoami';\n" +
                    "    }\n" +
                    "}\n" +
                    "\n" +
                    "stage 'label based'\n" +
                    "echo 'wait for executor'\n" +
                    "node {\n" +
                    "    stage 'things using node'\n" +
                    "    for (int i=0; i<200; i++) {\n" +
                    "        echo \"we waited for this $i seconds\"    \n" +
                    "    }\n" +
                    "}", true));
            // Horrifying reflection because you can't do a cast b/w normal and JMH class
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
                .include(PipelineBenchmark.class.getName() + ".*")
                // Set the following options as needed
                .mode (Mode.AverageTime)
                .timeUnit(TimeUnit.SECONDS)
                .warmupTime(TimeValue.seconds(300))
                .warmupIterations(1)
                .measurementTime(TimeValue.seconds(60))
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
