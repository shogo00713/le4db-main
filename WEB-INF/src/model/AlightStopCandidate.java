package model;

/**
 * 降車停留所の候補
 * @stopId 停留所ID
 * @stopName 停留所名
 * @lat 緯度
 * @lon 経度
 */
public class AlightStopCandidate {
    public final int stopId;
    public final String stopName;
    public final double lat;
    public final double lon;

        public AlightStopCandidate(int stopId, String stopName, double lat, double lon) {
            this.stopId = stopId;
            this.stopName = stopName;
            this.lat = lat;
            this.lon = lon;
        }
    }