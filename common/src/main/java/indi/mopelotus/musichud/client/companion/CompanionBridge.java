package indi.mopelotus.musichud.client.companion;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.companion.protocol.CompanionWire;
import indi.mopelotus.musichud.companion.protocol.FramePublisher;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL32C;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Legacy renderer adapter: independent transparent framebuffer with bounded asynchronous PBO readback. */
public final class CompanionBridge {
 private static volatile FramePublisher publisher;
 private static boolean stopped, preferencesRestored, desktopOnlyFrame, drawingDesktop;
 private static volatile int fps=60, scale=2;
 private static long nextCaptureNanos;
 private static DesktopCanvas canvas;
 private static TextureTarget target;
 private static final Slot[] SLOTS={new Slot(),new Slot(),new Slot()};
 private static final class Slot {
  int buffer, bytes, width, height; long fence;
  FramePublisher owner; FramePublisher.Capture capture;
 }
 private CompanionBridge() {}
    public static boolean enabled() { return publisher != null; }
    public static boolean connected() {
        var current = publisher;
        return current != null && current.connected();
    }
    public static boolean requiresFrameUpdates() { return connected(); }
    public static boolean locked() {
        var current = publisher;
        return current == null || current.locked();
    }
    public static int scale() { return scale; }
    public static void setScale(int value) {
        scale = Math.clamp(value, 1, 4);
        ClientConfig.getInstance().setCompanionScale(scale);
    }
    public static int fps() { return fps; }
    public static void setFps(int value) {
        fps = value <= 30 ? 30 : 60;
        ClientConfig.getInstance().setCompanionFps(fps);
    }

    /** Called on the Minecraft thread by the preferences UI; saves the user's choice. */
    public static void setEnabled(boolean enabled) {
        if (stopped) return;
        ClientConfig.getInstance().setCompanionEnabled(enabled);
        setRuntimeEnabled(enabled);
    }

    /** Shutdown and rendering failures stop this session without erasing the saved preference. */
    private static void setRuntimeEnabled(boolean enabled) {
        if (stopped || enabled == enabled()) return;
        if (!enabled) {
            FramePublisher previous = publisher;
            publisher = null;
            if (previous != null) previous.close();
            CompanionDistribution.enabled(false);
            return;
        }
        try {
            publisher = new FramePublisher(CompanionWire.PORT);
            CompanionDistribution.enabled(true);
            MusicHud.LOGGER.info("Companion export listening on 127.0.0.1:{} (display only)", CompanionWire.PORT);
        } catch (IOException error) {
            MusicHud.LOGGER.warn("Cannot enable Companion export: {}", error.getMessage());
        }
    }

    public static void requestLocked(boolean locked) {
        var current = publisher;
        if (current == null) return;
        long generation = current.generation();
        Minecraft.getInstance().execute(() -> {
            if (publisher == current) current.setLocked(generation, locked);
        });
    }

    public static void setLocked(boolean locked) {
        var current = publisher;
        if (current != null) current.setLocked(locked);
    }


 public static DesktopCanvas desktopCanvas(){return RenderSystem.isOnRenderThread() && drawingDesktop ? canvas : null;}
 public static boolean renderingWithoutGameSurface(){return desktopOnlyFrame;}
 public static boolean prepareTick(){restorePreferencesOnce();return true;}
 public static boolean prepareFrame(){desktopOnlyFrame=connected() && org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(Minecraft.getInstance().getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) == org.lwjgl.glfw.GLFW.GLFW_TRUE;return true;}
 public static void extract(GuiGraphics graphics,DeltaTracker delta){HudRendererManager.getInstance().renderFrame(graphics,delta);}
    public static void restorePreferencesOnce() {
        if (stopped || preferencesRestored || !Minecraft.getInstance().isGameLoadFinished()) return;
        var config = ClientConfig.getInstance();
        fps = config.getCompanionFps();
        scale = config.getCompanionScale();
        preferencesRestored = true;
        setRuntimeEnabled(config.getCompanionEnabled());
    }


 public static void render(){
  if(stopped)return;
  restorePreferencesOnce();
  // VulkanMod owns no current GL context. Even polling an empty PBO queue
  // would enter native OpenGL and abort the JVM before Java can catch it.
  if(org.lwjgl.glfw.GLFW.glfwGetCurrentContext()==0L){
   if(enabled()){
    MusicHud.LOGGER.warn("Companion desktop export requires an OpenGL game renderer; disabling export for this session");
    setRuntimeEnabled(false);
   }
   return;
  }
  poll();
  if(!connected()){releaseGraphics();return;}
  Minecraft mc=Minecraft.getInstance();
  if(!mc.isGameLoadFinished())return;
  long now=System.nanoTime();if(now<nextCaptureNanos)return;
  nextCaptureNanos=Math.max(nextCaptureNanos+1_000_000_000L/fps,now);
  Slot free=null;for(Slot s:SLOTS)if(s.fence==0){free=s;break;}if(free==null)return;
  FramePublisher current=publisher;FramePublisher.Capture capture=current==null?null:current.capture();if(capture==null)return;
  int read=GL32C.glGetInteger(GL32C.GL_READ_FRAMEBUFFER_BINDING),draw=GL32C.glGetInteger(GL32C.GL_DRAW_FRAMEBUFFER_BINDING);
  int pack=GL32C.glGetInteger(GL32C.GL_PIXEL_PACK_BUFFER_BINDING),alignment=GL32C.glGetInteger(GL32C.GL_PACK_ALIGNMENT);
  int[] viewport=new int[4];GL32C.glGetIntegerv(GL32C.GL_VIEWPORT,viewport);
  Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix());
  VertexSorting sorting=RenderSystem.getVertexSorting();
  try{
   canvas=DesktopCanvas.of(ClientConfig.getInstance().getHudWidth(),ClientConfig.getInstance().getHudHeight(),scale);
   if(target==null || target.width!=canvas.width() || target.height!=canvas.height()){
    if(target!=null)target.destroyBuffers();target=new TextureTarget(canvas.width(),canvas.height(),true,Minecraft.ON_OSX);
    MusicHud.LOGGER.info("Companion capture device: {}",GL32C.glGetString(GL32C.GL_RENDERER));
   }
   target.setClearColor(0,0,0,0);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
   drawingDesktop=true;
   RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,canvas.guiWidth(),canvas.guiHeight(),0,1000,21000),VertexSorting.ORTHOGRAPHIC_Z);
   RenderSystem.getModelViewStack().pushMatrix().identity().translate(0,0,-11000);RenderSystem.applyModelViewMatrix();
   boolean visible;
   try{
    GuiGraphics graphics=new GuiGraphics(mc,mc.renderBuffers().bufferSource());
    visible=HudRendererManager.getInstance().renderDesktopFrame(graphics,graphics::flush);
   }finally{RenderSystem.getModelViewStack().popMatrix();RenderSystem.applyModelViewMatrix();}
   if(!visible){current.clear();return;}
   int bytes=CompanionWire.pixelBytes(canvas.width(),canvas.height());
   if(free.buffer==0)free.buffer=GL32C.glGenBuffers();
   GL32C.glBindBuffer(GL32C.GL_PIXEL_PACK_BUFFER,free.buffer);
   if(free.bytes!=bytes){GL32C.glBufferData(GL32C.GL_PIXEL_PACK_BUFFER,bytes,GL32C.GL_STREAM_READ);free.bytes=bytes;}
   free.width=canvas.width();free.height=canvas.height();free.owner=current;free.capture=capture;
   GL32C.glBindFramebuffer(GL32C.GL_READ_FRAMEBUFFER,target.frameBufferId);GL32C.glPixelStorei(GL32C.GL_PACK_ALIGNMENT,1);
   GL32C.glReadPixels(0,0,free.width,free.height,GL32C.GL_RGBA,GL32C.GL_UNSIGNED_BYTE,0L);
   free.fence=GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE,0);GL32C.glFlush();
  }catch(RuntimeException error){MusicHud.LOGGER.warn("Companion rendering failed; disabling desktop export",error);setRuntimeEnabled(false);}
  finally{
   drawingDesktop=false;canvas=null;
   RenderSystem.setProjectionMatrix(projection,sorting);
   GL32C.glBindBuffer(GL32C.GL_PIXEL_PACK_BUFFER,pack);GL32C.glPixelStorei(GL32C.GL_PACK_ALIGNMENT,alignment);
   GL32C.glBindFramebuffer(GL32C.GL_READ_FRAMEBUFFER,read);GL32C.glBindFramebuffer(GL32C.GL_DRAW_FRAMEBUFFER,draw);
   GL32C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
  }
 }
 private static void poll(){
  int previous=GL32C.glGetInteger(GL32C.GL_PIXEL_PACK_BUFFER_BINDING);
  try{for(Slot s:SLOTS){
   if(s.fence==0)continue;int status=GL32C.glClientWaitSync(s.fence,0,0);
   if(status==GL32C.GL_TIMEOUT_EXPIRED)continue;
   GL32C.glDeleteSync(s.fence);s.fence=0;
   if(status==GL32C.GL_WAIT_FAILED){setRuntimeEnabled(false);continue;}
   if(s.owner!=publisher || !s.owner.connected())continue;
   GL32C.glBindBuffer(GL32C.GL_PIXEL_PACK_BUFFER,s.buffer);
   ByteBuffer mapped=GL32C.glMapBufferRange(GL32C.GL_PIXEL_PACK_BUFFER,0,s.bytes,GL32C.GL_MAP_READ_BIT);
   if(mapped==null){setRuntimeEnabled(false);continue;}
   try{byte[] pixels=new byte[s.bytes];int stride=s.width*4;
    for(int row=0;row<s.height;row++)mapped.get((s.height-row-1)*stride,pixels,row*stride,stride);
    s.owner.publish(s.capture,s.width,s.height,pixels);
   }finally{GL32C.glUnmapBuffer(GL32C.GL_PIXEL_PACK_BUFFER);}
  }}finally{GL32C.glBindBuffer(GL32C.GL_PIXEL_PACK_BUFFER,previous);}
 }
 public static boolean shutdown(){setRuntimeEnabled(false);stopped=true;CompanionDistribution.shutdown();releaseGraphics();return true;}
 private static void releaseGraphics(){
  if(target!=null){target.destroyBuffers();target=null;}
  for(Slot s:SLOTS){if(s.fence!=0){GL32C.glDeleteSync(s.fence);s.fence=0;}if(s.buffer!=0){GL32C.glDeleteBuffers(s.buffer);s.buffer=0;s.bytes=0;}s.owner=null;s.capture=null;}
 }
}
