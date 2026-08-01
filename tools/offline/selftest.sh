#!/usr/bin/env bash
# SNTelegram self-test.
#
# No network, no JUnit, no server. Two probes drive the real code and print key=value lines; this
# script asserts on them. Everything needed is a JDK and bash, both of which come with Git for
# Windows and with any Linux runner - so the same command runs on the developer's box and in CI.
#
# What is actually being tested, and why these things:
#
#   * The forum-topic traps. Telegram makes every message in a topic look like a reply, and the
#     General topic is not thread 1 no matter what the tutorials say. Both were found by reading
#     the Bot API server's source, and both would produce a bridge that seems to work.
#   * The networking core against a real socket. FakeTelegram speaks the Bot API over localhost,
#     so TelegramApi, the polling loop and the send queue are exercised as they ship - including
#     the failure paths, which the real Telegram will not produce on demand.
#   * The injection boundary. A stranger in a Telegram group must not be able to put MiniMessage
#     into a Minecraft chat line.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

CLASSES="build/offline/classes"
STUBS="build/offline/stubs"
WORK="build/selftest"

if [[ ! -d "$CLASSES" ]]; then
  echo "build first: bash tools/offline/build-offline.sh" >&2
  exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK"

FAILED=0

# The harness is compiled into its own directory, never into build/offline/classes. A stray class
# there would end up in the jar and would also show up as a difference in the CI comparison
# against the real paper-api build, which compiles only src/main/java.
echo "==> harness"
find tools/offline/harness -name '*.java' > "$WORK/harness-sources.txt"
javac -nowarn -encoding UTF-8 --release 17 -cp "$CLASSES" -d "$WORK/harness" "@$WORK/harness-sources.txt"

# ':' on Linux, ';' under Git Bash on Windows - the one place the difference cannot be avoided.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *) SEP=":" ;;
esac
CP="$CLASSES$SEP$WORK/harness"

echo "==> логика: темы форума, модерация, конфиг, безопасность"
java -Dstdout.encoding=UTF-8 -cp "$CP" harness.LogicProbe > "$WORK/logic.txt"

echo "==> сеть: long polling, очередь отправки, ошибки Telegram"
java -Dstdout.encoding=UTF-8 -cp "$CP" harness.CoreProbe > "$WORK/core.txt"

expect() { # expect <файл> <описание> <точная строка key=value>
  if grep -qxF -- "$3" "$1"; then
    echo "  ok   $2"
  else
    echo "  FAIL $2"
    echo "         ожидалось: $3"
    echo "         получено:  $(grep -F -- "${3%%=*}=" "$1" || echo '<строки нет>')"
    FAILED=$((FAILED + 1))
  fi
}

L="$WORK/logic.txt"
C="$WORK/core.txt"

echo
echo "==> темы форума Telegram"
expect "$L" "неявный реплай в теме не считается реплаем"      "topic.implicit-reply-ignored=true"
expect "$L" "  message_thread_id читается"                    "topic.thread-read=47"
expect "$L" "  текст сообщения сохраняется"                   "topic.text-kept=обычная строка"
expect "$L" "настоящий реплай распознаётся"                   "topic.real-reply-detected=true"
expect "$L" "  и указывает на нужное сообщение"               "topic.real-reply-target=8123"
expect "$L" "служебное сообщение о создании темы отбрасывается" "topic.service-ignored=true"
expect "$L" "у General-темы нет message_thread_id"            "topic.general-has-no-thread=true"
expect "$L" "  и параметр не отправляется (а не 1)"           "topic.general-omits-parameter=true"
expect "$L" "  константа General равна нулю"                  "topic.general-id-is-zero=true"
expect "$L" "реплай из другой темы не путается с локальным"   "topic.external-reply-not-local=true"
expect "$L" "медиа без текста не теряется"                    "media.kind=фото"
expect "$L" "  и не считается пустым"                         "media.not-empty=true"

echo
echo "==> модерация реплаем"
expect "$L" "цель берётся из сообщения, на которое ответили"  "mod.reply-target=Steve"
expect "$L" "  разбирается действие"                          "mod.action=MUTE"
expect "$L" "  разбирается длительность"                      "mod.duration=600000"
expect "$L" "  разбирается причина"                           "mod.reason=мат в чате"
expect "$L" "русские команды работают"                        "mod.ru-action=BAN"
expect "$L" "  русские длительности тоже"                     "mod.ru-duration-days=7"
expect "$L" "суффикс @имя_бота отбрасывается"                 "mod.bot-suffix-stripped=true"
expect "$L" "без реплая цель берётся из аргумента"            "mod.named-target=Notch"
expect "$L" "  и длительность за ней"                         "mod.named-duration-hours=48"
expect "$L" "длительность не съедается как ник"               "mod.missing-target-detected=true"
expect "$L" "бан без срока — навсегда"                        "mod.ban-defaults-permanent=true"
expect "$L" "мут без срока — десять минут"                    "mod.mute-default-minutes=10"
expect "$L" "обычный текст не принимается за команду"         "mod.plain-text-not-command=true"
expect "$L" "неизвестный глагол не выполняется"               "mod.unknown-verb-not-command=true"
expect "$L" "команды /rcon не существует"                     "mod.no-rcon-verb=true"
expect "$L" "  и /console тоже"                               "mod.no-console-verb=true"

echo
echo "==> длительности и русские окончания"
expect "$L" "10m"                                             "time.10m=600000"
expect "$L" "  кириллическое 10м"                             "time.10м-cyrillic=600000"
expect "$L" "  голое число — минуты"                          "time.bare-number-is-minutes=1800000"
expect "$L" "  «навсегда»"                                    "time.permanent=true"
expect "$L" "  причина не принимается за срок"                "time.not-a-duration=true"
expect "$L" "  срок ограничен годом"                          "time.capped-at-a-year=true"
expect "$L" "1 минуту"                                        "plural.1=1 минуту"
expect "$L" "2 минуты"                                        "plural.2=2 минуты"
expect "$L" "5 минут"                                         "plural.5=5 минут"
expect "$L" "11 минут — исключение русского правила"          "plural.11=11 минут"
expect "$L" "21 минуту"                                       "plural.21=21 минуту"

echo
echo "==> конфиг"
expect "$L" "пустой конфиг не запускает мост"                 "config.empty-not-usable=true"
expect "$L" "  и объясняет, чего не хватает"                  "config.empty-warns=true"
expect "$L" "  но даёт рабочую тему по умолчанию"             "config.empty-has-default-topic=true"
expect "$L" "заполненный конфиг пригоден"                     "config.usable=true"
expect "$L" "  chat-id не теряет точность в 64 битах"         "config.chat-id-exact=true"
expect "$L" "  завышенный poll-timeout прижимается к 50"      "config.poll-clamped=50"
expect "$L" "  темы читаются"                                 "config.topics=3"
expect "$L" "  русские имена событий понимаются"              "config.ru-event-names=админка"
expect "$L" "  событие уходит в свою тему"                    "config.routes-death=91"
expect "$L" "  тема находится по thread-id"                   "config.by-thread=админка"
expect "$L" "  и General находится без него"                  "config.by-thread-general=основной"
expect "$L" "  корректный конфиг не даёт предупреждений"      "config.no-warnings=true"
expect "$L" "битый токен замечен"                             "config.warns-bad-token=true"
expect "$L" "  положительный chat-id замечен"                 "config.warns-positive-chat=true"
expect "$L" "  неизвестное событие названо"                   "config.warns-unknown-event=true"
expect "$L" "  дубль thread-id замечен"                       "config.warns-duplicate-thread=true"

echo
echo "==> память реплаев"
expect "$L" "сообщение помнит своего игрока"                  "index.recalls=Steve"
expect "$L" "  чужой id не выдаёт игрока"                     "index.unknown-is-null=true"
expect "$L" "  размер ограничен"                              "index.bounded=true"
expect "$L" "  свежие записи остаются"                        "index.keeps-newest=true"

echo
echo "==> безопасность"
expect "$L" "MiniMessage из Telegram не разбирается"          "safety.single-literal-span=true"
expect "$L" "  текст доходит дословно"                        "safety.text-unchanged=true"
expect "$L" "  и без стилей"                                  "safety.no-styles=true"
expect "$L" "HTML в сторону Telegram экранируется"            "safety.html-escaped=true"

echo
echo "==> модерация из консоли сервера"
expect "$L" "консоль получает тот же текст без разметки"      "console.strips-tags=🔇 Steve не сможет писать в чат 10 минут."
expect "$L" "  сущности разворачиваются обратно"             "console.unescapes=true"
expect "$L" "  экранирование обратимо"                        "console.roundtrip=true"
expect "$L" "команда из консоли разбирается тем же кодом"     "console.action=MUTE"
expect "$L" "  ник"                                           "console.target=Steve"
expect "$L" "  срок"                                          "console.duration=600000"
expect "$L" "  причина"                                       "console.reason=флуд в чате"
expect "$L" "  результат совпадает с командой из Telegram"    "console.matches-telegram=true"
expect "$L" "  русские глаголы работают и здесь"              "console.ru=true"
expect "$L" "без ника из консоли — ошибка, а не догадка"      "console.needs-target=true"
expect "$L" "  срок не принимается за ник"                    "console.duration-not-a-name=true"
expect "$L" "свои подкоманды не перехватываются модерацией"   "console.reload-not-moderation=true"
expect "$L" "  и import тоже"                                 "console.import-not-moderation=true"
expect "$L" "опечатка не выполняется молча"                   "console.typo-unknown=true"

echo
echo "==> Telegram по настоящему сокету"
expect "$C" "токен уходит в пути запроса"                     "api.token-in-path=true"
expect "$C" "  getMe разбирается"                             "api.getme-username=sntelegram_test_bot"
expect "$C" "токен не попадает в сообщения об ошибках"        "api.redacts-full-token=true"
expect "$C" "  включая секретную половину"                    "api.redacts-secret-half=true"
expect "$C" "  но контекст ошибки сохраняется"                "api.redaction-keeps-context=true"
expect "$C" "401 распознан как неверный токен"                "err.401-unauthorized=true"
expect "$C" "429 распознан как временный"                     "err.429-retryable=true"
expect "$C" "  retry_after переведён в миллисекунды"          "err.429-retry-after-ms=7000"
expect "$C" "400 не повторяется"                              "err.400-not-retryable=true"
expect "$C" "HTML вместо JSON не роняет мост"                 "err.html-body-handled=true"
expect "$C" "все обновления доставлены"                       "poll.delivered=3"
expect "$C" "  и по порядку"                                  "poll.in-order=true"
expect "$C" "  запрашивается allowed_updates"                 "poll.asks-for-allowed-updates=true"
expect "$C" "  лишние типы не запрашиваются"                  "poll.limits-update-types=true"
expect "$C" "накопившееся за простой отбрасывается"           "backlog.skipped=true"
expect "$C" "  а свежее доставляется"                         "backlog.fresh-delivered=true"
expect "$C" "  через offset=-1"                               "backlog.probe-used-offset-minus-1=true"
expect "$C" "после 429 сообщение всё же уходит"               "outbox.sent-after-429=1"
expect "$C" "  запрос повторяется"                            "outbox.retried=true"
expect "$C" "  message_thread_id в теле"                      "outbox.body-has-thread=true"
expect "$C" "  parse_mode=HTML в теле"                        "outbox.body-has-html=true"
echo
echo "==> ошибки настройки объясняются словами"
expect "$C" "несуществующая тема названа по номеру"          "explain.missing-topic=true"
expect "$C" "  и указан файл, где её чинить"                 "explain.points-at-config=true"
expect "$C" "  и что для General нужен 0"                    "explain.explains-general=true"
expect "$C" "  повтор не засоряет лог"                       "explain.not-repeated=true"
expect "$C" "неверный chat-id объяснён"                      "explain.chat-not-found=true"
expect "$C" "нехватка прав бота объяснена"                   "explain.no-rights=true"

echo
echo "==> очередь отправки"
expect "$C" "очередь ограничена"                              "outbox.bounded=true"
expect "$C" "  переполнение видно вызывающему"                "outbox.reports-overflow=true"
expect "$C" "  потери считаются"                              "outbox.counts-drops=true"

# ---------------------------------------------------------------- shipped config.yml
#
# config.yml is read by SNTelegram's own MiniYaml, not by Bukkit, and its header is a wall of
# box-drawing comments. A stray quote or colon in that banner would not raise anything - it would
# quietly hand back every default instead, and the admin's settings would be ignored with no error
# to go on. So the real loader is run against the real file.
echo
echo "==> поставляемый config.yml"
mkdir -p "$WORK/probe"
cat > "$WORK/probe/ShippedConfigProbe.java" <<'EOF'
package harness;

import network.somikyy.sntelegram.core.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ShippedConfigProbe {
    public static void main(String[] args) throws Exception {
        Config c = Config.load(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        System.out.println("shipped.parses=true");
        System.out.println("shipped.topics=" + c.topics().size());
        System.out.println("shipped.poll=" + c.pollSeconds());
        System.out.println("shipped.moderation=" + c.moderationEnabled());
        System.out.println("shipped.per-second=" + (int) c.sendsPerSecond());
        System.out.println("shipped.queue=" + c.queueSize());
        // A fresh file has no token and no chat id, so it must warn about exactly those two and
        // nothing else - any third warning means the shipped file contradicts itself.
        System.out.println("shipped.warnings=" + c.warnings().size());
        System.out.println("shipped.template-has-user="
                + c.templates().fromTelegram().contains("<user>"));
        System.out.println("shipped.chat-template-has-player="
                + c.templates().chat().contains("{player}"));
    }
}
EOF
javac -nowarn -encoding UTF-8 --release 17 -cp "$CLASSES" -d "$WORK/harness" "$WORK/probe/ShippedConfigProbe.java"
java -Dstdout.encoding=UTF-8 -cp "$CP" harness.ShippedConfigProbe \
    src/main/resources/config.yml > "$WORK/shipped.txt"

S="$WORK/shipped.txt"
expect "$S" "баннер не ломает разбор"                         "shipped.parses=true"
expect "$S" "  темы прочитаны"                                "shipped.topics=3"
expect "$S" "  poll-timeout прочитан"                         "shipped.poll=30"
expect "$S" "  модерация включена"                            "shipped.moderation=true"
expect "$S" "  лимит отправки прочитан"                       "shipped.per-second=25"
expect "$S" "  размер очереди прочитан"                       "shipped.queue=500"
expect "$S" "  ровно два предупреждения: токен и chat-id"     "shipped.warnings=2"
expect "$S" "  MiniMessage-шаблон использует <user>"          "shipped.template-has-user=true"
expect "$S" "  HTML-шаблон использует {player}"               "shipped.chat-template-has-player=true"

# ---------------------------------------------------------------- API surface
#
# The offline half of the stub guard: the descriptors this build emits must equal the ones
# recorded in git. CI runs the other half, comparing the same sources built against the real
# paper-api - that is what ties the recorded file to reality.
echo
echo "==> поверхность серверного API"
bash tools/offline/api-surface.sh "$CLASSES" > "$WORK/api-surface.txt"
if diff -u tools/offline/api-surface.txt "$WORK/api-surface.txt" > "$WORK/api-surface.diff" 2>&1; then
  echo "  ok   дескрипторы совпадают с tools/offline/api-surface.txt"
else
  echo "  FAIL дескрипторы разошлись с записанной поверхностью:"
  sed 's/^/         /' "$WORK/api-surface.diff"
  echo "         Если изменение осознанное, перезапиши:"
  echo "         bash tools/offline/api-surface.sh build/offline/classes > tools/offline/api-surface.txt"
  FAILED=$((FAILED + 1))
fi

# ---------------------------------------------------------------- layering
#
# core/ must never touch the server. That invariant is what makes this whole file possible: the
# probes above run the real bridge logic on a machine with no server jar.
echo
echo "==> инвариант слоёв"
if grep -rn "import org\.bukkit\|import io\.papermc\|import net\.kyori\|import com\.destroystokyo" \
        src/main/java/network/somikyy/sntelegram/core > "$WORK/layering.txt" 2>/dev/null; then
  echo "  FAIL core импортирует серверные классы:"
  sed 's/^/         /' "$WORK/layering.txt"
  FAILED=$((FAILED + 1))
else
  echo "  ok   core не импортирует ни одного серверного класса"
fi

echo
if [[ $FAILED -eq 0 ]]; then
  echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
else
  echo "$FAILED проверк(и) не прошли"
  exit 1
fi
