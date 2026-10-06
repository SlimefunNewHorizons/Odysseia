package org.metamechanists.odysseia.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.metamechanists.odysseia.Odysseia;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModerationListenerTest {

    private final Logger logger = Logger.getLogger("ModerationListenerTest");
    private Handler handler;

    @AfterEach
    void removeLogHandler() {
        if (handler != null) {
            logger.removeHandler(handler);
        }
    }

    @Test
    void acceptsOnlyConfiguredDiscordWebhookEndpoints() {
        assertFalse(ModerationListener.isValidModerationWebhook(null));
        assertFalse(ModerationListener.isValidModerationWebhook(""));
        assertFalse(ModerationListener.isValidModerationWebhook("REPLACE_ME"));
        assertFalse(ModerationListener.isValidModerationWebhook("http://discord.com/api/webhooks/1/x"));
        assertTrue(ModerationListener.isValidModerationWebhook(
                "https://discord.com/api/webhooks/123456789012345678/token"));
    }

    @Test
    void logsInvalidWebhookOnlyOncePerListener() {
        Odysseia plugin = mock(Odysseia.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        when(config.getString("discord.webhook-moderation-url", "")).thenReturn("");

        AtomicInteger warnings = new AtomicInteger();
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                        && record.getMessage().contains("Webhook de moderación inválido")) {
                    warnings.incrementAndGet();
                }
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);

        ModerationListener listener = new ModerationListener(plugin);
        assertNull(listener.moderationWebhook());
        assertNull(listener.moderationWebhook());
        assertNull(listener.moderationWebhook());
        assertEquals(1, warnings.get());
    }
}
