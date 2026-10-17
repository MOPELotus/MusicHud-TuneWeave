package indi.mopelotus.musichud.server.api;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class SodaSignerProcessTest {
    static class Child extends Process {
        final CompletableFuture<Process> ended=new CompletableFuture<>();
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor() throws InterruptedException {try{ended.get();return 0;}catch(ExecutionException e){throw new AssertionError(e);}}
        public int exitValue(){if(isAlive())throw new IllegalThreadStateException();return 0;}
        public boolean isAlive(){return !ended.isDone();}
        public CompletableFuture<Process> onExit(){return ended.copy();}
        public void destroy(){ended.complete(this);}
        public Process destroyForcibly(){destroy();return this;}
    }
    @Test void stopBeforeHealthNeverStartsMainAndRetiresSigner() throws Exception {
        var signer=new Child();var starts=new AtomicInteger();
        var pair=new SodaSignerProcess(signer,()->{starts.incrementAndGet();return new Child();},()->false);
        pair.destroy();assertTrue(pair.waitFor(3,TimeUnit.SECONDS));assertFalse(signer.isAlive());assertEquals(0,starts.get());
    }
    @Test void stopDuringMainCreationRetiresLateChildBeforeReleasingLease() throws Exception {
        var signer=new Child();var main=new Child();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pair=new SodaSignerProcess(signer,()->{entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){throw new IOException(e);}return main;},()->true);
        assertTrue(entered.await(3,TimeUnit.SECONDS));pair.destroyForcibly();assertTrue(pair.isAlive());
        release.countDown();assertTrue(pair.waitFor(3,TimeUnit.SECONDS));assertFalse(main.isAlive());assertFalse(signer.isAlive());
    }
    @Test void eitherChildExitRetiresItsPeerAndCancelledObserverDoesNotHideExit() throws Exception {
        for(boolean signerExits:new boolean[]{false,true}) {
            var signer=new Child();var main=new Child();var launched=new CountDownLatch(1);
            var pair=new SodaSignerProcess(signer,()->{launched.countDown();return main;},()->true);
            assertTrue(launched.await(3,TimeUnit.SECONDS));pair.getInputStream();pair.onExit().cancel(true);
            (signerExits?signer:main).destroy();assertTrue(pair.waitFor(3,TimeUnit.SECONDS));assertFalse(main.isAlive());assertFalse(signer.isAlive());
        }
    }
    @Test void failedMainLaunchCleansSignerAndDoesNotExposeLaunchSecrets() throws Exception {
        var signer=new Child();var pair=new SodaSignerProcess(signer,()->{throw new IOException("synthetic secret");},()->true);
        assertTrue(pair.waitFor(3,TimeUnit.SECONDS));assertFalse(signer.isAlive());assertEquals(-1,pair.getErrorStream().read());
    }
}
