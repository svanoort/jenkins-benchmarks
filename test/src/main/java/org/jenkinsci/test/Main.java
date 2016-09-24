package org.jenkinsci.test;

import hudson.model.FreeStyleProject;
import java.util.concurrent.Callable;
import jenkins.model.Jenkins;
import org.jenkinsci.harness.JenkinsMaskedClassesRunner;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

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
    public static void main(String[] args) throws Exception {
        JenkinsMaskedClassesRunner.run(Main.class);
    }
}
