package model;

// ポート一覧の1行分のデータを表すクラス
/**
 * @param portId       ポートID
 * @param operatorName 事業者名
 * @param portName     ポート名
 * @param bikes        配置されている自転車の台数
 * @param freeDocks    空きドックの台数
 */
public class PortRow {
    public final int portId;
    public final String operatorName;
    public final String portName;
    public final int bikes;
    public final int freeDocks;

    public PortRow(int portId, String operatorName, String portName, int bikes, int freeDocks) {
        this.portId = portId;
        this.operatorName = operatorName;
        this.portName = portName;
        this.bikes = bikes;
        this.freeDocks = freeDocks;
    }
}