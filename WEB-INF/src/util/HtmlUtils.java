package util;

public class HtmlUtils {
    
    public static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
    
    public static String safe(String value, String defaultValue) {
        return (value == null) ? defaultValue : value;
    }
    
    public static String option(String value, String label, String selected) {
        String sel = value.equals(selected) ? " selected" : "";
        return "<option value=\"" + esc(value) + "\"" + sel + ">" + esc(label) + "</option>";
    }
}
