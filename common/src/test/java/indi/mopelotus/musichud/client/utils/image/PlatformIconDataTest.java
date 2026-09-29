package indi.mopelotus.musichud.client.utils.image;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
class PlatformIconDataTest {
    @Test void everyPlatformHasWhiteVisiblePixelsAndTransparentBackground() throws Exception {
        for (var platform : TuneWeavePlatform.values()) {
            var image = ImageIO.read(new ByteArrayInputStream(PlatformIconData.png(platform,64)));
            int visible=0, transparent=0;
            for (int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                int color=image.getRGB(x,y);
                if((color>>>24)==0) transparent++;
                else { visible++; assertEquals(0xffffff,color&0xffffff,platform.apiName()); }
            }
            assertTrue(visible>0,platform.apiName()); assertTrue(transparent>0,platform.apiName());
            if(platform==TuneWeavePlatform.MIGU) {
                assertEquals(0,image.getRGB(0,0)>>>24);
                assertEquals(0,image.getRGB(256,256)>>>24,"The pink hole inside the note stays transparent");
            }
        }
    }
}
