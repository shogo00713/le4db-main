package util;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import dao.PortQueries;
import model.NearByPorts;
import model.Stop;
import model.TransferBikeTransit;
import model.TransferPath;
import model.TransferTransitBike;
import model.TransitDirectPlan;
import model.WalkPath;

public class RouteSearchUtils {
    
    // より良い自転車か調べる
    public static boolean betterBikeTransit(TransferBikeTransit a, TransferBikeTransit b) {
        java.time.LocalTime ae = java.time.LocalTime.parse(a.endTime);
        java.time.LocalTime be = java.time.LocalTime.parse(b.endTime);
        int c = ae.compareTo(be);
        if (c != 0) return c < 0;

        return a.totalMin < b.totalMin;
    }
    
    // より良い直通か調べる
    public static boolean betterDirect(TransitDirectPlan a, TransitDirectPlan b) {
        LocalTime ea = LocalTime.parse(a.endTime);
        LocalTime eb = LocalTime.parse(b.endTime);

        if (ea.isBefore(eb))
            return true;
        if (ea.isAfter(eb))
            return false;

        // 同着なら「最後の徒歩が短い方」を優先（＝手前下車のゴミを落とす）
        int wa = (a.walk2 == null) ? 0 : a.walk2.dist;
        int wb = (b.walk2 == null) ? 0 : b.walk2.dist;
        if (wa != wb)
            return wa < wb;

        // さらに同じなら所要時間が短い方
        return a.totalMin < b.totalMin;
    }

    // より良い乗換か調べる
    public static boolean betterTransfer(TransferPath a, TransferPath b) {
        java.time.LocalTime ae = java.time.LocalTime.parse(a.endTime);
        java.time.LocalTime be = java.time.LocalTime.parse(b.endTime);
        int c = ae.compareTo(be);
        if (c != 0) return c < 0;

        if (a.totalMin != b.totalMin) return a.totalMin < b.totalMin;

        java.time.LocalTime as = java.time.LocalTime.parse(a.startTime);
        java.time.LocalTime bs = java.time.LocalTime.parse(b.startTime);
        return as.compareTo(bs) > 0; // 同着同時間なら待ちが少ない(出発が遅い)方
    }

    // より良い乗換＋自転車か調べる
    public static boolean betterTransferBike(TransferTransitBike a, TransferTransitBike b) {
        java.time.LocalTime ae = java.time.LocalTime.parse(a.endTime);
        java.time.LocalTime be = java.time.LocalTime.parse(b.endTime);
        int c = ae.compareTo(be);
        if (c != 0) return c < 0;

        if (a.totalMin != b.totalMin) return a.totalMin < b.totalMin;

        java.time.LocalTime as = java.time.LocalTime.parse(a.startTime);
        java.time.LocalTime bs = java.time.LocalTime.parse(b.startTime);
        return as.compareTo(bs) > 0;
    }


    // ポートを事業者ごとにグループ化
    public static java.util.Map<Integer, java.util.List<NearByPorts>> groupPortsByOperator(List<NearByPorts> ports) {
        java.util.Map<Integer, java.util.List<NearByPorts>> portsByOperator = new java.util.HashMap<>();
        for (NearByPorts p : ports) {
            portsByOperator.computeIfAbsent(p.operatorId, k -> new java.util.ArrayList<>()).add(p);
        }
        return portsByOperator;
    }

    // 停留所リスト周辺のポートを収集（重複除去）
    public static List<NearByPorts> collectNearbyPortsFromStops(
            Connection conn, List<Stop> stops, int radiusM, int portLimit, boolean needBikes, boolean needDocks)
            throws SQLException {
        java.util.Map<Integer, NearByPorts> portById = new java.util.HashMap<>();
        for (Stop stop : stops) {
            List<NearByPorts> ports = PortQueries.getNearByPorts(conn, stop.lat, stop.lon, radiusM, portLimit, needBikes, needDocks);
            for (NearByPorts p : ports) {
                portById.putIfAbsent(p.portId, p);
            }
        }
        return new ArrayList<>(portById.values());
    }

    // 乗り換えが同一地点かどうかを判断する関数
    public static boolean isZeroWalk(WalkPath w) {
        if (w == null)
            return true;
        boolean same = (w.fromName != null && w.toName != null && w.fromName.equals(w.toName));
        return same && w.min == 0 && w.dist == 0;
    }


}
