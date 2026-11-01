package indi.mopelotus.musichud.companion.desktop;

import java.awt.*;
import javax.imageio.ImageIO;

/** Windows lifecycle action only; HUD content and controls remain in Minecraft. */
final class ExitTray implements AutoCloseable {
    private TrayIcon icon;

    ExitTray(Runnable exit) {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) return;
        try {
            if (!SystemTray.isSupported()) return;
            try (var input = ExitTray.class.getResourceAsStream("/companion-icon.png")) {
                if (input == null) return;
                var image = ImageIO.read(input);
                EventQueue.invokeAndWait(() -> {
                    var menu = new PopupMenu();
                    var item = new MenuItem("Exit Companion / 退出");
                    item.addActionListener(event -> exit.run());
                    menu.add(item);
                    icon = new TrayIcon(image, "MusicHud Companion", menu);
                    icon.setImageAutoSize(true);
                    try { SystemTray.getSystemTray().add(icon); }
                    catch (AWTException error) { icon = null; System.err.println("Tray unavailable: " + error.getMessage()); }
                });
            }
        } catch (Exception | LinkageError error) { System.err.println("Tray unavailable: " + error.getMessage()); }
    }

    @Override public void close() {
        if (icon == null) return;
        try { EventQueue.invokeAndWait(() -> SystemTray.getSystemTray().remove(icon)); }
        catch (Exception error) { System.err.println("Could not remove tray icon: " + error.getMessage()); }
    }
}
