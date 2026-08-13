package com.manujerozx.mimicvoice.voice;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import de.maxhenkel.voicechat.api.VoicechatServerApi;

class MimicVoicechatAddonTest {

    @Test
    void closeMakesAddonInertAndUnregistersVolumeCategory() {
        AtomicBoolean categoryUnregistered = new AtomicBoolean();
        VoicechatServerApi api = (VoicechatServerApi) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {VoicechatServerApi.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("unregisterVolumeCategory")) {
                        categoryUnregistered.set(true);
                    }
                    return null;
                });
        MimicVoicechatAddon addon = new MimicVoicechatAddon(null, Logger.getAnonymousLogger());

        addon.initialize(api);
        assertSame(api, addon.serverApi());
        addon.close();
        addon.close();

        assertNull(addon.serverApi());
        assertTrue(categoryUnregistered.get());
    }
}
