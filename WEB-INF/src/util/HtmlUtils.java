package util;

public class HtmlUtils {
    
    /**
     * HTMLエスケープ
     * @param text 入力文字列
     * @return エスケープ後の文字列
     */
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
    
    /**
     * null安全な文字列取得
     * @param value 入力文字列
     * @param defaultValue デフォルト値
     * @return valueがnullの場合はdefaultValue、それ以外はvalue
     */
    public static String safe(String value, String defaultValue) {
        return (value == null) ? defaultValue : value;
    }
    
    /**
     * optionタグ生成
     * @param value optionの値
     * @param label optionの表示ラベル
     * @param selected 選択されている値
     * @return optionタグの文字列
     */
    public static String option(String value, String label, String selected) {
        String sel = value.equals(selected) ? " selected" : "";
        return "<option value=\"" + esc(value) + "\"" + sel + ">" + esc(label) + "</option>";
    }

    /**
     * nullを空文字に変換
     * @param s 入力文字列
     * @return sがnullの場合は空文字、それ以外はs
     */ 
    public static String nvl(String s){
        return s==null ? "" : s;
    }
}
