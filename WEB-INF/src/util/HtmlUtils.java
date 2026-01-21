package util;

import java.sql.SQLException;

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

    /**
     * オブジェクトを文字列に変換（nullは空文字）
     * @param obj 入力オブジェクト
     * @return objがnullの場合は空文字、それ以外はobj.toString()
     */
    public static String toStr(Object obj) {
        return obj == null ? "" : obj.toString();
    }

    /**
     * 優先非空文字列取得
     * @param primary
     * @param fallback
     * @return
     */
    public static String preferNonEmpty(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * カラム存在チェック
     * @param meta
     * @param columnLabel
     * @return
     * @throws SQLException
     */
    public static boolean hasColumn(java.sql.ResultSetMetaData meta, String columnLabel) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (columnLabel.equalsIgnoreCase(meta.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全な色コード取得
     * @param raw 入力文字列
     * @return HEX形式の色コード（例: #ff0000）
     */
    public static String safeColor(String raw) {
        if (raw == null)
            return "#9ca3af"; // gray-400
        String v = raw.trim();
        if (v.isEmpty())
            return "#9ca3af";

        // 既にHEX形式ならそのまま
        if (v.matches("^[0-9a-fA-F]{6}$"))
            return "#" + v;
        if (v.matches("^#[0-9a-fA-F]{6}$"))
            return v;

        // 色名（あなたのINSERTに合わせる）
        String key = v.toLowerCase();
        switch (key) {
            case "blue":
                return "#2563eb";
            case "orange":
                return "#f97316";
            case "gray":
            case "grey":
                return "#6b7280";
            case "green":
                return "#16a34a";
            case "red":
                return "#dc2626";
            case "purple":
                return "#7c3aed";
            default:
                return "#9ca3af";
        }
    }

    /**
     * 連絡先情報の正規化
     * @param contact 連絡先情報
     * @param operatorName 事業者名
     * @return 正規化された連絡先情報
     */
    public static String normalizeContact(String contact, String operatorName) {
        if (contact != null) {
            String trimmed = contact.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        if (operatorName != null && !operatorName.isEmpty()) {
            return operatorName + " (連絡先未登録)";
        }
        return "連絡先未登録";
    }



}
