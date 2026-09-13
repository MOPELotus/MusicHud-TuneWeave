package indi.mopelotus.musichud.client.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.interfaces.IKeyRegistryServiceDefinition;
import indi.mopelotus.musichud.platform.Environment;
import net.minecraft.client.KeyMapping;

import java.util.function.Supplier;

public interface IKeyRegistryService extends IKeyRegistryServiceDefinition {
    void register(KeyMapping keyMapping, Runnable action);

    static IKeyRegistryService getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<IKeyRegistryServiceDefinition> supplier = platform.getKeyRegistryServiceSupplier();
        if (supplier != null) {
            IKeyRegistryServiceDefinition iKeyRegistryServiceDefinition = supplier.get();
            if (iKeyRegistryServiceDefinition instanceof IKeyRegistryService iKeyRegistryService) {
                return iKeyRegistryService;
            } else if (iKeyRegistryServiceDefinition != null) {
                throw new IllegalStateException("KeyRegistryService should implements IKeyRegistryService, not IKeyRegistryServiceDefinition");
            }
        }
        throw new UnsupportedOperationException();
    }
}