package be.kiwazo.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Next events from a published iCal feed (e.g. an iCloud "public calendar"
 * webcal:// link). The URL lives in files/calendar.txt on the TV, never in the
 * source. The feed is downloaded only when the home screen appears and the
 * cached copy is older than 30 minutes: no background polling.
 *
 * Supports what calendar apps actually publish: all-day and timed events,
 * TZID/UTC/floating times, RRULE (DAILY/WEEKLY/MONTHLY/YEARLY with INTERVAL,
 * COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH), EXDATE and moved occurrences
 * (RECURRENCE-ID).
 */
final class CalendarFeed {

    static final class Occurrence {
        final String title;
        final boolean allDay;
        final Instant start, end;          // timed events
        final LocalDate startDate, endDate; // all-day events, end exclusive

        Occurrence(String title, boolean allDay, Instant start, Instant end, LocalDate startDate, LocalDate endDate) {
            this.title = title;
            this.allDay = allDay;
            this.start = start;
            this.end = end;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        boolean isOver(Instant now, LocalDate today) {
            return allDay ? !endDate.isAfter(today) : !end.isAfter(now);
        }
    }

    private static final String TAG = "TvHome";
    private static final long MAX_AGE_MS = 30 * 60 * 1000L;
    private static final int KEEP = 20;
    private static final int HORIZON_DAYS = 180;
    private static final DateTimeFormatter LOCAL_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final Pattern BYDAY = Pattern.compile("([+-]?\\d*)(MO|TU|WE|TH|FR|SA|SU)");

    private CalendarFeed() {}

    /** Upcoming occurrences, or null when no calendar is configured. */
    static List<Occurrence> load(Context ctx, boolean allowNetwork) {
        String url = readUrl(new File(ctx.getExternalFilesDir(null), "calendar.txt"));
        if (url == null) return null;
        File cache = new File(ctx.getCacheDir(), "calendar-" + Integer.toHexString(url.hashCode()) + ".ics");
        boolean stale = !cache.isFile() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS;
        if (allowNetwork && stale) {
            try {
                download(url, cache);
            } catch (IOException e) {
                Log.w(TAG, "calendar fetch failed: " + e.getMessage());
            }
        }
        if (!cache.isFile()) return Collections.emptyList();
        try (InputStream in = new FileInputStream(cache)) {
            return upcoming(parse(in), Instant.now(), ZoneId.systemDefault());
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "calendar parse failed", e);
            return Collections.emptyList();
        }
    }

    static boolean isStale(Context ctx) {
        String url = readUrl(new File(ctx.getExternalFilesDir(null), "calendar.txt"));
        if (url == null) return false;
        File cache = new File(ctx.getCacheDir(), "calendar-" + Integer.toHexString(url.hashCode()) + ".ics");
        return !cache.isFile() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS;
    }

    // ---------------------------------------------------------------- network

    private static String readUrl(File f) {
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("webcal://")) return "https://" + line.substring(9);
                if (line.startsWith("https://") || line.startsWith("http://")) return line;
            }
        } catch (IOException ignored) {
            // No calendar then.
        }
        return null;
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "TvHome/1.0");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            File tmp = new File(dest.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(dest)) throw new IOException("rename failed");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- parsing

    private static final class Prop {
        final String name;
        final Map<String, String> params;
        final String value;

        Prop(String name, Map<String, String> params, String value) {
            this.name = name;
            this.params = params;
            this.value = value;
        }
    }

    private static final class Event {
        final Map<String, List<Prop>> props = new HashMap<>();

        Prop first(String name) {
            List<Prop> l = props.get(name);
            return l == null || l.isEmpty() ? null : l.get(0);
        }

        List<Prop> all(String name) {
            List<Prop> l = props.get(name);
            return l == null ? Collections.emptyList() : l;
        }
    }

    /** A DTSTART/DTEND-like value: either a date or a zoned date-time. */
    private static final class When {
        final LocalDate date;
        final ZonedDateTime time;

        When(LocalDate date, ZonedDateTime time) {
            this.date = date;
            this.time = time;
        }

        boolean allDay() {
            return date != null;
        }

        long key(ZoneId sys) {
            return allDay() ? date.atStartOfDay(sys).toEpochSecond() : time.toEpochSecond();
        }
    }

    private static List<Event> parse(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        String raw;
        while ((raw = r.readLine()) != null) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + raw.substring(1)); // unfold
            } else {
                lines.add(raw);
            }
        }
        List<Event> events = new ArrayList<>();
        Event cur = null;
        int nested = 0;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                cur = new Event();
                nested = 0;
            } else if (cur != null && line.startsWith("BEGIN:")) {
                nested++; // e.g. VALARM inside an event
            } else if (cur != null && line.startsWith("END:")) {
                if (nested > 0) {
                    nested--;
                } else if (line.equals("END:VEVENT")) {
                    events.add(cur);
                    cur = null;
                }
            } else if (cur != null && nested == 0) {
                Prop p = parseProp(line);
                if (p != null) cur.props.computeIfAbsent(p.name, k -> new ArrayList<>()).add(p);
            }
        }
        return events;
    }

    private static Prop parseProp(String line) {
        int colon = -1;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ':' && !quoted) {
                colon = i;
                break;
            }
        }
        if (colon <= 0) return null;
        String[] head = line.substring(0, colon).split(";");
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < head.length; i++) {
            int eq = head[i].indexOf('=');
            if (eq > 0) params.put(head[i].substring(0, eq).toUpperCase(), head[i].substring(eq + 1).replace("\"", ""));
        }
        return new Prop(head[0].toUpperCase(), params, line.substring(colon + 1));
    }

    private static When parseWhen(Prop p, String value) {
        String v = value.trim();
        if ("DATE".equalsIgnoreCase(p.params.get("VALUE")) || v.length() == 8) {
            return new When(LocalDate.parse(v.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE), null);
        }
        boolean utc = v.endsWith("Z");
        LocalDateTime ldt = LocalDateTime.parse(utc ? v.substring(0, v.length() - 1) : v, LOCAL_DT);
        ZoneId zone = ZoneId.systemDefault();
        if (utc) {
            zone = ZoneOffset.UTC;
        } else if (p.params.containsKey("TZID")) {
            try {
                zone = ZoneId.of(p.params.get("TZID"));
            } catch (RuntimeException ignored) {
                // Unknown TZID: treat as local time.
            }
        }
        return new When(null, ldt.atZone(zone));
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    // -------------------------------------------------------------- expansion

    private static List<Occurrence> upcoming(List<Event> events, Instant now, ZoneId sys) {
        LocalDate today = now.atZone(sys).toLocalDate();
        Instant horizon = now.plus(HORIZON_DAYS, ChronoUnit.DAYS);

        // Moved/edited instances of recurring events replace the original instance.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (Event e : events) {
            Prop rid = e.first("RECURRENCE-ID");
            Prop uid = e.first("UID");
            if (rid == null || uid == null) continue;
            try {
                overridden.computeIfAbsent(uid.value, k -> new HashSet<>()).add(parseWhen(rid, rid.value).key(sys));
            } catch (RuntimeException ignored) {
                // Unparseable instance id: keep the original.
            }
        }

        List<Occurrence> out = new ArrayList<>();
        for (Event e : events) {
            try {
                collect(e, overridden, now, today, horizon, sys, out);
            } catch (RuntimeException ex) {
                Log.w(TAG, "skipping unparseable event", ex);
            }
        }
        out.sort((a, b) -> Long.compare(startKey(a, sys), startKey(b, sys)));
        return out.size() > KEEP ? new ArrayList<>(out.subList(0, KEEP)) : out;
    }

    private static long startKey(Occurrence o, ZoneId sys) {
        return o.allDay ? o.startDate.atStartOfDay(sys).toEpochSecond() : o.start.getEpochSecond();
    }

    private static void collect(Event e, Map<String, Set<Long>> overridden, Instant now, LocalDate today,
                                Instant horizon, ZoneId sys, List<Occurrence> out) {
        Prop status = e.first("STATUS");
        if (status != null && "CANCELLED".equalsIgnoreCase(status.value.trim())) return;
        Prop dtstart = e.first("DTSTART");
        if (dtstart == null) return;
        Prop summary = e.first("SUMMARY");
        String title = summary == null ? "" : unescape(summary.value);

        When start = parseWhen(dtstart, dtstart.value);
        Prop dtend = e.first("DTEND");
        Prop durationProp = e.first("DURATION");
        long days = 1;
        Duration length = Duration.ZERO;
        if (start.allDay()) {
            if (dtend != null) days = Math.max(1, ChronoUnit.DAYS.between(start.date, parseWhen(dtend, dtend.value).date));
            else if (durationProp != null) days = Math.max(1, parseDuration(durationProp.value).toDays());
        } else {
            if (dtend != null) {
                When end = parseWhen(dtend, dtend.value);
                length = end.allDay() ? Duration.ofDays(1) : Duration.between(start.time, end.time);
            } else if (durationProp != null) {
                length = parseDuration(durationProp.value);
            }
        }

        Prop rrule = e.first("RRULE");
        if (rrule == null || e.first("RECURRENCE-ID") != null) {
            add(out, title, start, days, length, now, today);
            return;
        }

        Set<Long> skip = new HashSet<>();
        for (Prop ex : e.all("EXDATE")) {
            for (String v : ex.value.split(",")) {
                if (!v.trim().isEmpty()) skip.add(parseWhen(ex, v).key(sys));
            }
        }
        Prop uid = e.first("UID");
        if (uid != null && overridden.containsKey(uid.value)) skip.addAll(overridden.get(uid.value));

        ZoneId zone = start.allDay() ? sys : start.time.getZone();
        LocalDateTime base = start.allDay() ? start.date.atStartOfDay() : start.time.toLocalDateTime();
        LocalDateTime windowEnd = LocalDateTime.ofInstant(horizon, zone);
        for (LocalDateTime c : expand(parseRule(rrule.value), base, zone, windowEnd)) {
            When w = start.allDay() ? new When(c.toLocalDate(), null) : new When(null, c.atZone(zone));
            if (skip.contains(w.key(sys))) continue;
            add(out, title, w, days, length, now, today);
        }
    }

    private static void add(List<Occurrence> out, String title, When w, long days, Duration length,
                            Instant now, LocalDate today) {
        Occurrence o = w.allDay()
                ? new Occurrence(title, true, null, null, w.date, w.date.plusDays(days))
                : new Occurrence(title, false, w.time.toInstant(), w.time.toInstant().plus(length), null, null);
        if (!o.isOver(now, today)) out.add(o);
    }

    private static Map<String, String> parseRule(String value) {
        Map<String, String> rule = new HashMap<>();
        for (String part : value.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) rule.put(part.substring(0, eq).toUpperCase(), part.substring(eq + 1));
        }
        return rule;
    }

    /** All occurrence starts (local time in the event's zone) up to windowEnd. */
    private static List<LocalDateTime> expand(Map<String, String> rule, LocalDateTime dtstart, ZoneId zone,
                                              LocalDateTime windowEnd) {
        String freq = rule.getOrDefault("FREQ", "");
        int interval = Math.max(1, intOr(rule.get("INTERVAL"), 1));
        int count = intOr(rule.get("COUNT"), -1);
        LocalDateTime until = null;
        if (rule.containsKey("UNTIL")) {
            String u = rule.get("UNTIL");
            if (u.length() == 8) {
                until = LocalDate.parse(u, DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59, 59);
            } else if (u.endsWith("Z")) {
                until = LocalDateTime.parse(u.substring(0, u.length() - 1), LOCAL_DT)
                        .atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime();
            } else {
                until = LocalDateTime.parse(u, LOCAL_DT);
            }
        }
        List<String> byDay = list(rule.get("BYDAY"));
        List<Integer> byMonthDay = ints(rule.get("BYMONTHDAY"));
        List<Integer> byMonth = ints(rule.get("BYMONTH"));

        List<LocalDateTime> out = new ArrayList<>();
        int n = 0;
        for (int period = 0; period < 20_000; period++) {
            List<LocalDateTime> cands = new ArrayList<>();
            switch (freq) {
                case "DAILY":
                    cands.add(dtstart.plusDays((long) period * interval));
                    break;
                case "WEEKLY": {
                    LocalDateTime week = dtstart.plusWeeks((long) period * interval);
                    if (byDay.isEmpty()) {
                        cands.add(week);
                    } else {
                        LocalDate monday = week.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                        for (String d : byDay) {
                            Matcher m = BYDAY.matcher(d);
                            if (m.matches()) {
                                cands.add(monday.plusDays(dow(m.group(2)).getValue() - 1).atTime(dtstart.toLocalTime()));
                            }
                        }
                    }
                    break;
                }
                case "MONTHLY":
                    monthCandidates(YearMonth.from(dtstart).plusMonths((long) period * interval),
                            dtstart, byDay, byMonthDay, cands);
                    break;
                case "YEARLY": {
                    int year = dtstart.getYear() + period * interval;
                    List<Integer> months = byMonth.isEmpty()
                            ? Collections.singletonList(dtstart.getMonthValue()) : byMonth;
                    for (int month : months) {
                        monthCandidates(YearMonth.of(year, month), dtstart, byDay, byMonthDay, cands);
                    }
                    break;
                }
                default:
                    out.add(dtstart);
                    return out;
            }
            Collections.sort(cands);
            for (LocalDateTime c : cands) {
                if (c.isBefore(dtstart)) continue;
                if ((until != null && c.isAfter(until)) || (count >= 0 && n >= count) || c.isAfter(windowEnd)) {
                    return out;
                }
                n++;
                out.add(c);
            }
        }
        return out;
    }

    private static void monthCandidates(YearMonth ym, LocalDateTime dtstart, List<String> byDay,
                                        List<Integer> byMonthDay, List<LocalDateTime> cands) {
        if (!byMonthDay.isEmpty()) {
            for (int d : byMonthDay) {
                int day = d > 0 ? d : ym.lengthOfMonth() + d + 1;
                if (day >= 1 && day <= ym.lengthOfMonth()) cands.add(ym.atDay(day).atTime(dtstart.toLocalTime()));
            }
        } else if (!byDay.isEmpty()) {
            for (String d : byDay) {
                Matcher m = BYDAY.matcher(d);
                if (!m.matches()) continue;
                DayOfWeek dow = dow(m.group(2));
                String ord = m.group(1);
                LocalDate first = ym.atDay(1).with(TemporalAdjusters.firstInMonth(dow));
                if (ord.isEmpty() || ord.equals("+") || ord.equals("-")) {
                    for (LocalDate x = first; x.getMonth() == ym.getMonth(); x = x.plusWeeks(1)) {
                        cands.add(x.atTime(dtstart.toLocalTime()));
                    }
                } else {
                    int k = Integer.parseInt(ord);
                    LocalDate x = k > 0 ? first.plusWeeks(k - 1)
                            : ym.atEndOfMonth().with(TemporalAdjusters.lastInMonth(dow)).minusWeeks(-k - 1);
                    if (x.getMonth() == ym.getMonth()) cands.add(x.atTime(dtstart.toLocalTime()));
                }
            }
        } else if (dtstart.getDayOfMonth() <= ym.lengthOfMonth()) {
            cands.add(ym.atDay(dtstart.getDayOfMonth()).atTime(dtstart.toLocalTime()));
        }
    }

    private static Duration parseDuration(String v) {
        String s = v.trim();
        Matcher weeks = Pattern.compile("^([+-]?)P(\\d+)W$").matcher(s);
        if (weeks.matches()) return Duration.ofDays(7L * Integer.parseInt(weeks.group(2)));
        return Duration.parse(s);
    }

    private static DayOfWeek dow(String two) {
        switch (two) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            default: return DayOfWeek.SUNDAY;
        }
    }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        for (String s : v.split(",")) if (!s.trim().isEmpty()) out.add(s.trim().toUpperCase());
        return out;
    }

    private static List<Integer> ints(String v) {
        List<Integer> out = new ArrayList<>();
        for (String s : list(v)) {
            try {
                out.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                // Skip malformed values.
            }
        }
        return out;
    }

    private static int intOr(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
