package indi.mopelotus.musichud.neoforge;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.screen.MainFragment;
import indi.mopelotus.musichud.client.ui.screen.MusicHudScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings("unused")
public class ConfigScreenFactory implements IConfigScreenFactory {
    @Override
    @NotNull
    public Screen createScreen(@NotNull ModContainer container, @NotNull Screen modListScreen) {
        var fragment = new MainFragment();
        fragment.setDefaultSelectedIndex(3); // Setting page
        return MusicHudScreen.createScreen(fragment, null, modListScreen, MusicHud.DISPLAY_NAME);
    }
}
