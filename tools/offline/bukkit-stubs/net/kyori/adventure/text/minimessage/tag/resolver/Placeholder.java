package net.kyori.adventure.text.minimessage.tag.resolver;

import net.kyori.adventure.text.ComponentLike;

public final class Placeholder {
    private Placeholder() { }
    public static TagResolver.Single component(String key, ComponentLike value) { return null; }
    public static TagResolver.Single unparsed(String key, String value) { return null; }
}
