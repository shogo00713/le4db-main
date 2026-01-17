import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/*

 データベース接続の初期化と接続取得を行うクラス

 initialize メソッド : 設定ファイルの読み込み
 getConnection メソッド : 接続の取得する

 */

public class DatabaseConfig {
    
    // データベース接続のための情報を保持するフィールド
    private static String _hostname = null;
    private static String _dbname   = null;
    private static String _username = null;
    private static String _password = null;

    // 初期化済みか管理するフラグ
    private static boolean initialized = false;

    // 初期化メソッド (le4db.iniファイルを読み込む)
    public static synchronized void initialize(String iniFilePath) throws Exception {

        if (initialized) {
            return; // すでに初期化されている場合は何もしない
        }

        try(FileInputStream fis = new FileInputStream(iniFilePath)) {
            Properties prop = new java.util.Properties();
            prop.load(fis);

            // 設定値を取得して保存
            _hostname = prop.getProperty("hostname");
            _dbname   = prop.getProperty("dbname");
            _username = prop.getProperty("username");
            _password = prop.getProperty("password");

            // PostgreSQLドライバのロード
            Class.forName("org.postgresql.Driver");

            // 初期化完了
            initialized = true;
        
        }   catch (Exception e) {
            System.err.println("データベース初期化エラー: " + e.getMessage());
            throw e;
        }
    }

    // データベース接続を取得するメソッド
    public static Connection getConnection() throws SQLException {

        // 初期化されていなければエラー
        if (!initialized) {
            throw new SQLException("データベースが初期化されていません。");
        }
        
        // URLを組み立てて接続を返す
        String url = "jdbc:postgresql://" + _hostname + ":5432/" + _dbname;
        return DriverManager.getConnection(url, _username, _password);
    }

}