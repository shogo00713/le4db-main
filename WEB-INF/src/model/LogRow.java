package model;

    // ログ行データ保持用クラス
public class LogRow {
    public final int logId;
    public final String movedAt;
    public final int operatorId;
    public final String operatorName;
    public final int fromPortId;
    public final String fromName;
    public final int toPortId;
    public final String toName;
    public final int movedBikes;
    public final String source;

    public LogRow(int logId, String movedAt, int operatorId, String operatorName,
               int fromPortId, String fromName, int toPortId, String toName, int movedBikes, String source) {
        this.logId = logId;
        this.movedAt = movedAt;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.fromPortId = fromPortId;
        this.fromName = fromName;
        this.toPortId = toPortId;
        this.toName = toName;
        this.movedBikes = movedBikes;
        this.source = source;
    }
}