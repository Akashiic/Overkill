package com.akashic.overkill.pack;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** A small JSON writer for the pack files (maps keep their order). */
final class Json {

    private Json() {}

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0);
        return sb.append('\n')
            .toString();
    }

    private static void write(StringBuilder sb, Object o, int indent) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            string(sb, (String) o);
        } else if (o instanceof Number) {
            number(sb, ((Number) o).doubleValue());
        } else if (o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            for (Iterator<? extends Map.Entry<?, ?>> it = m.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<?, ?> e = it.next();
                pad(sb, indent + 1);
                string(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                sb.append(it.hasNext() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (o instanceof List) {
            List<?> l = (List<?>) o;
            boolean flat = true;
            for (Object x : l) {
                flat &= !(x instanceof Map) && !(x instanceof List);
            }
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (!flat) {
                    sb.append('\n');
                    pad(sb, indent + 1);
                }
                write(sb, l.get(i), indent + 1);
                if (i < l.size() - 1) {
                    sb.append(flat ? ", " : ",");
                }
            }
            if (!flat && !l.isEmpty()) {
                sb.append('\n');
                pad(sb, indent);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not JSON: " + o.getClass());
        }
    }

    private static void number(StringBuilder sb, double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            String s = String.format(java.util.Locale.ROOT, "%.4f", d);
            s = s.replaceAll("0+$", "")
                .replaceAll("\\.$", "");
            sb.append(s);
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }
}
