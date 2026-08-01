package net.kyori.adventure.text.serializer.plain;

import net.kyori.adventure.text.Component;

public interface PlainTextComponentSerializer {
    static PlainTextComponentSerializer plainText() { return null; }
    String serialize(Component component);
}
