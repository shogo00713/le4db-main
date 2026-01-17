package model;

/**
 * 直通経路情報
 * @tripId 乗車ID
 * @routeName 路線名
 * @routeColor 路線カラー (16進RGB)
 * @tripName 便名
 * @fromStopId 出発停留所ID
 * @fromStopName 出発停留所名
 * @depTime 出発時刻 ("HH:mm")
 * @toStopId 到着停留所ID
 * @toStopName 到着停留所名
 * @arrTime 到着時刻 ("HH:mm")
 */
public class TransitPath {
    public final int tripId;
    public final String routeName;
    public final String routeColor;
    public final String tripName;
    public final int fromStopId;
    public final String fromStopName;
    public final String depTime;
    public final int toStopId;
    public final String toStopName;
    public final String arrTime;

    public TransitPath(int tripId, String routeName, String routeColor, String tripName, int fromStopId, String fromStopName, String depTime, int toStopId, String toStopName, String arrTime) {
        this.tripId = tripId;
        this.routeName = routeName;
        this.routeColor = routeColor;
        this.tripName = tripName;
        this.fromStopId = fromStopId;
        this.fromStopName = fromStopName;
        this.depTime = depTime;
        this.toStopId = toStopId;
        this.toStopName = toStopName;
        this.arrTime = arrTime;
    }
}