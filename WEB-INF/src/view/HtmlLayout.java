package view;

import java.io.PrintWriter;
import javax.servlet.http.HttpServletRequest;

public class HtmlLayout {
  public static void renderHead(PrintWriter out, HttpServletRequest req, String title, String bodyClass) {
    out.println("<!DOCTYPE html><html lang='ja'><head>");
    out.println("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'>");
    out.println("<title>" + title + "</title>");
    out.println("<link rel='stylesheet' href='" + req.getContextPath() + "/static/app.css'/>");
    out.println("</head><body class='" + bodyClass + "'>");
    out.println("<div class='app'>");
  }
  public static void renderFoot(PrintWriter out) {
    out.println("</div></body></html>");
  }
}
