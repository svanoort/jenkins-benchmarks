package org.jenkinsci.harness;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Extend me to create a basic jenkins benchmark, and create benchmark-annotated methods
 */
@State(Scope.Benchmark)
public abstract class BaseBenchmark {
    public JenkinsMaskedClassesRunner maskedClassesRunner = new JenkinsMaskedClassesRunner();
    public BaseBenchmark actualRunnable;

    public abstract Class<? extends BaseBenchmark> getTestClass();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        maskedClassesRunner.startup();
        Class c = maskedClassesRunner.testLoader.loadClass(getTestClass().getName());
        Object o = c.newInstance();
        actualRunnable = (BaseBenchmark) (o);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        maskedClassesRunner.shutdown();
    }

    @Setup(Level.Invocation)  // Invocation-level for cases where you're testing for a test interval
    public void setupInvocationInvoker() throws Exception {
        actualRunnable.setupInvocation();  // Indirection due to classloading fun
    }

    /** Override me to provide some per-iteration behavior using internals of this benchmark */
    public void setupInvocation(){
        // NO-OP
    }

    @TearDown(Level.Invocation)  // Invocation-level for cases where you're testing for a test interval
    public void tearDownInvocationInvoker() throws Exception {
        actualRunnable.tearDownInvocation();  // Indirection due to classloading fun
    }

    /** Override me to provide some per-iteration behavior using internals of this benchmark */
    public void tearDownInvocation(){
        // NO-OP, override me to provide some per-iteration behavior
    }
}
