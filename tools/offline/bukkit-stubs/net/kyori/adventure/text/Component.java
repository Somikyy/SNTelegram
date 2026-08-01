package net.kyori.adventure.text;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public interface Component extends ComponentLike {
    static TextComponent empty() { return null; }
    static TextComponent text(String content) { return null; }
    static TextComponent text(String content, TextColor color) { return null; }
    Component append(Component component);
    Component color(TextColor colour);
    Component decorate(TextDecoration decoration);
}
