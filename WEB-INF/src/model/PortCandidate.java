package model;

/**
 * ポートの検索候補
 * @portId ポートID
 * @operatorId 運営者ID
 * @operatorName 運営者名
 * @operatorContact 運営者連絡先
 * @portName ポート名
 * @lat 緯度
 * @lon 経度
 * @distance centerからの直線距離(m)
 */
public class PortCandidate {
    public final int portId;
    public final int operatorId;
    public final String operatorName;
    public final String operatorContact;
    public final String portName;
    public final double lat;
    public final double lon;
    public final int distance;

    public PortCandidate(int portId, int operatorId, String operatorName, String operatorContact, String portName, double lat, double lon, int distance) {
        this.portId = portId;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.operatorContact = operatorContact;
        this.portName = portName;
        this.lat = lat;
        this.lon = lon;
        this.distance = distance;
    }
}