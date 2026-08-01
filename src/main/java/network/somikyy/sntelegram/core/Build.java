/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sntelegram.core;

/**
 * The version, in one place.
 *
 * <p>Gradle substitutes {@code ${version}} into {@code plugin.yml} at build time, and the offline
 * build script reads the number out of this file to do the same - so both paths produce a jar
 * that reports the same version, and there is one line to change when cutting a release.
 *
 * <p>Numbering follows the suite: {@code <Minecraft era>.<month>.<release>}. 26.8.1 is the first
 * release of August 2026, for the 26.x era.
 */
public final class Build {

    public static final String VERSION = "26.8.1";

    public static final String CHANNEL = "https://t.me/somikyy";

    private Build() {
    }
}
