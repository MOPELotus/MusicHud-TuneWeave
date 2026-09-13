package indi.mopelotus.musichud.fabric.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.screen.MusicHudScreen;
import indi.mopelotus.musichud.client.ui.screen.MainFragment;

public class ModMenuConfigurer implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            var fragment = new MainFragment();
            fragment.setDefaultSelectedIndex(3);//Setting page
            return MusicHudScreen.createScreen(fragment, null, parent, MusicHud.DISPLAY_NAME);
        };
    }
}
