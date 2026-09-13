package indi.mopelotus.musichud.client.ui.hud.metadata;

import indi.mopelotus.musichud.client.utils.image.ImageTextureData;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public class BackgroundImages {
    public volatile ImageTextureData current;
    public volatile float imageAspect;

    public BackgroundImages(ImageTextureData current, float imageAspect) {
        this.current = current;
        this.imageAspect = imageAspect;
    }
}
