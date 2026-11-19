package org.example;

import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.*;

/** Отдельный упорядоченный список приборов во вкладке «График». */
public final class ChartDb {
    private ChartDb() {}
    public static void init() throws SQLException {
        try (Connection c=Db.openConnection(); Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS chart_devices (device_id INTEGER PRIMARY KEY, added_at TEXT NOT NULL DEFAULT (datetime('now')), sort_order INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(device_id) REFERENCES devices(id))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_chart_devices_order ON chart_devices(sort_order, added_at)");
        }
    }
    public static JSONArray list() throws SQLException { return query(null,false); }
    public static JSONArray search(String q) throws SQLException { return query(q,true); }
    private static JSONArray query(String q, boolean search) throws SQLException {
        JSONArray out=new JSONArray();
        String sql="SELECT d.id,d.serial_number,d.name,d.location,d.details_json,c.added_at,c.sort_order,CASE WHEN c.device_id IS NULL THEN 0 ELSE 1 END in_chart FROM devices d LEFT JOIN chart_devices c ON c.device_id=d.id WHERE COALESCE(d.is_deleted,0)=0 "+
          (search ? "AND (?='' OR CAST(d.id AS TEXT) LIKE ? OR COALESCE(d.serial_number,'') LIKE ? OR COALESCE(d.name,'') LIKE ? OR COALESCE(d.location,'') LIKE ? OR COALESCE(d.details_json,'') LIKE ?) " : "AND c.device_id IS NOT NULL ")+
          (search ? "ORDER BY CASE WHEN CAST(d.id AS TEXT)=? THEN 0 WHEN COALESCE(d.serial_number,'')=? THEN 1 ELSE 2 END,CASE WHEN c.device_id IS NOT NULL THEN 1 ELSE 0 END,d.id DESC LIMIT 60" : "ORDER BY c.sort_order,c.added_at,c.device_id");
        try(Connection c=Db.openConnection();PreparedStatement p=c.prepareStatement(sql)){
            if(search){String term=q==null?"":q.trim(),like="%"+term+"%";p.setString(1,term);for(int i=2;i<=6;i++)p.setString(i,like);p.setString(7,term);p.setString(8,term);}
            try(ResultSet r=p.executeQuery()){while(r.next()){
                JSONObject o=new JSONObject().put("id",r.getLong("id")).put("serialNumber",n(r.getString("serial_number"))).put("name",n(r.getString("name"))).put("location",n(r.getString("location"))).put("addedAt",n(r.getString("added_at"))).put("sortOrder",r.getInt("sort_order")).put("inChart",r.getInt("in_chart")==1);
                try{JSONObject d=new JSONObject(r.getString("details_json")==null?"{}":r.getString("details_json"));o.put("sensorType",d.optString("sensorType","")).put("positionName",d.optString("positionName",d.optString("position",""))).put("groupName",d.optString("groupName",""));}catch(Exception ignored){}
                out.put(o);
            }}
        }
        return out;
    }
    public static void add(long id)throws SQLException{
        try(Connection c=Db.openConnection()){
            try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM devices WHERE id=? AND COALESCE(is_deleted,0)=0")){p.setLong(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("Прибор не найден или находится в корзине");}}
            try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO chart_devices(device_id,added_at,sort_order) VALUES(?,datetime('now'),(SELECT COALESCE(MAX(sort_order),0)+1 FROM chart_devices))")){p.setLong(1,id);p.executeUpdate();}
        }
    }
    public static boolean remove(long id)throws SQLException{try(Connection c=Db.openConnection();PreparedStatement p=c.prepareStatement("DELETE FROM chart_devices WHERE device_id=?")){p.setLong(1,id);return p.executeUpdate()>0;}}
    public static void reorder(JSONArray ids)throws SQLException{
        try(Connection c=Db.openConnection()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE chart_devices SET sort_order=? WHERE device_id=?")){for(int i=0;i<ids.length();i++){p.setInt(1,i+1);p.setLong(2,ids.getLong(i));p.addBatch();}p.executeBatch();c.commit();}catch(Exception e){c.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e);}}
    }
    private static String n(String s){return s==null?"":s;}
}