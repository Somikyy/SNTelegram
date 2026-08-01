package net.kyori.adventure.text.minimessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Compile-only stub.
 *
 * <p>The single-argument deserialize takes Object, not String, and that is not a mistake: in real
 * Adventure it is inherited from ComponentSerializer, whose input type variable is unbounded and
 * therefore erases to Object. Declaring it as String here would emit a call nobody implements.
 */
public interface MiniMessage {
    static MiniMessage miniMessage() { return null; }
    Component deserialize(Object input);
    Component deserialize(String input, TagResolver... tagResolvers);
}
