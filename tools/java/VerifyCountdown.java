import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用真实引擎（java.util.regex，与 Kotlin Regex 同源）验证倒计时正则。
 *
 * 为什么需要这个：Python re 与 Java Pattern 在字符类边界上并不完全一致，
 * 而 SkipTextNormalizer 的字符类里含 【】— 等字符，必须用真实引擎验证编译与匹配。
 * Python 脚本（tools/verify_countdown_patterns.py）负责快速回归，
 * 本程序负责"真引擎"确认。
 */
public class VerifyCountdown {

    private static final String FULL_WIDTH_DIGITS = "０-９";
    private static final String FULL_WIDTH_SPACE = "\u3000";
    private static final String DIGITS = "0-9" + FULL_WIDTH_DIGITS;
    // 注意：连字符必须在字符类末尾，否则 】-— 会被当作逆序范围而报错
    private static final String CONNECTORS =
            "\\s" + FULL_WIDTH_SPACE + "sS秒后·\\.:：,，、（）\\(\\)\\[\\]【】|/\\\\-—";

    private static final Pattern TRIM_EDGE =
            Pattern.compile("^[" + DIGITS + CONNECTORS + "]+|[" + DIGITS + CONNECTORS + "]+$");

    private static final String[] KEYWORDS = {
            "跳过广告", "跳過廣告", "关闭广告", "關閉廣告", "跳过按钮",
            "跳过", "跳過", "略过", "略過", "关闭", "關閉",
            "skip ad", "close ad", "skip", "close", "dismiss", "cancel",
            "✕", "✖", "✗", "×", "⨯", "╳", "❌", "❎",
    };

    private static int failures = 0;

    static String keywordOf(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String text = raw.trim();
        String lower = text.toLowerCase();
        for (String kw : KEYWORDS) {
            if (text.contains(kw) || lower.contains(kw.toLowerCase())) return kw;
        }
        String stripped = TRIM_EDGE.matcher(text).replaceAll("").trim();
        return stripped.isEmpty() ? null : stripped;
    }

    static String patternFor(String keyword) {
        String escaped = Pattern.quote(keyword);
        for (char c : keyword.toCharArray()) {
            if (Character.isDigit(c)) return escaped;
        }
        return escaped
                + "|[" + DIGITS + "]*[" + CONNECTORS + "]*" + escaped
                + "|" + escaped + "[" + CONNECTORS + "]*[" + DIGITS + "]+";
    }

    static void check(String label, boolean got, boolean expect) {
        boolean ok = got == expect;
        if (!ok) failures++;
        System.out.printf("%s %-46s got=%s expect=%s%n", ok ? "✓" : "✗", label, got, expect);
    }

    public static void main(String[] args) {
        System.out.println("=== A) 真实引擎能否编译这些字符类 ===");
        System.out.println("TRIM_EDGE      = " + TRIM_EDGE.pattern());
        String sample = patternFor("跳过");
        Pattern.compile(sample);
        System.out.println("patternFor(跳过) 编译通过，长度 " + sample.length());

        System.out.println();
        System.out.println("=== B) keywordOf：两种排列归一 ===");
        String[][] kwCases = {
                {"跳过", "跳过"}, {"跳过3", "跳过"}, {"跳过 3", "跳过"}, {"跳过3s", "跳过"},
                {"跳过 5 秒", "跳过"}, {"3跳过", "跳过"}, {"3 跳过", "跳过"},
                {"5秒后跳过", "跳过"}, {"3s跳过", "跳过"}, {"3s后跳过", "跳过"},
                {"关闭3", "关闭"}, {"3关闭", "关闭"}, {"跳过广告", "跳过广告"},
                {"跳过广告 3", "跳过广告"}, {"skip", "skip"}, {"skip 3", "skip"},
                {"3 skip", "skip"}, {"✕", "✕"}, {"3", null}, {"", null},
        };
        for (String[] c : kwCases) {
            String got = keywordOf(c[0]);
            boolean ok = (got == null) ? (c[1] == null) : got.equals(c[1]);
            if (!ok) failures++;
            System.out.printf("%s %-16s -> %-12s (期望 %s)%n",
                    ok ? "✓" : "✗", "\"" + c[0] + "\"", got == null ? "null" : "\"" + got + "\"",
                    c[1] == null ? "null" : "\"" + c[1] + "\"");
        }

        System.out.println();
        System.out.println("=== C) 学习模式：倒计时变化后仍命中（IGNORE_CASE，与 RuleMatcher 一致）===");
        String[][] learnCases = {
                {"跳过3", "跳过5"}, {"跳过3", "3跳过"}, {"跳过3", "5秒后跳过"},
                {"3跳过", "5跳过"}, {"3跳过", "跳过5"}, {"5秒后跳过", "2秒后跳过"},
                {"跳过", "跳过5"}, {"跳过", "3跳过"}, {"关闭2", "5关闭"},
        };
        for (String[] c : learnCases) {
            String kw = keywordOf(c[0]);
            Pattern p = Pattern.compile(patternFor(kw), Pattern.CASE_INSENSITIVE);
            boolean hit = p.matcher(c[1]).find();
            check("学到 \"" + c[0] + "\" → 命中 \"" + c[1] + "\"", hit, true);
        }

        System.out.println();
        System.out.println("=== D) 内置规则条件（真实引擎，CASE_INSENSITIVE）===");
        String[] builtinPatterns = {
                "跳过|跳過|略过|跳过广告|关闭广告",
                "[0-9０-９]+\\s*[sS秒]?\\s*后?\\s*(跳过|关闭|跳過|關閉)|(跳过|关闭|跳過|關閉)\\s*(后|in|after)?\\s*[sS秒]?\\s*[0-9０-９]+",
                "skip|close|dismiss",
                "[0-9０-９]*\\s*[sS]?\\s*(skip|close|dismiss)|(skip|close|dismiss)\\s*(in|after)?\\s*[0-9０-９]+\\s*[sS]?",
        };
        Pattern[] compiled = new Pattern[builtinPatterns.length];
        for (int i = 0; i < builtinPatterns.length; i++) {
            compiled[i] = Pattern.compile(builtinPatterns[i], Pattern.CASE_INSENSITIVE);
        }
        String[] samples = {"跳过", "跳过3", "3跳过", "跳过 5 秒", "5秒后跳过", "3s跳过",
                "关闭2", "2关闭", "skip 3", "3 skip", "Skip in 3s"};
        for (String s : samples) {
            boolean hit = false;
            for (Pattern p : compiled) {
                if (p.matcher(s).find()) { hit = true; break; }
            }
            check("内置规则命中 \"" + s + "\"", hit, true);
        }

        System.out.println();
        System.out.println("=== E) 防误触：无关文本不应命中 ===");
        String[] negatives = {"立即下载", "同意并继续", "打开", "查看详情", "领取奖励", "5折优惠", "3件商品"};
        for (String s : negatives) {
            boolean hit = false;
            for (Pattern p : compiled) {
                if (p.matcher(s).find()) { hit = true; break; }
            }
            check("无关文本不命中 \"" + s + "\"", hit, false);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("全部通过 ✓（真实引擎 java.util.regex）");
        } else {
            System.out.println("失败用例数: " + failures + " ✗");
            System.exit(1);
        }
    }
}
