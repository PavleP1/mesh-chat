package app.mesh;
import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.bluetooth.*;
import java.io.*;
import java.util.*;

public class MeshMidlet extends MIDlet implements CommandListener, DiscoveryListener {
  static final String UUID_STR = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  Display d; String me = "Nokia" + (System.currentTimeMillis() % 1000);
  Vector links = new Vector(), seen = new Vector(), log = new Vector(), devs = new Vector();
  String group = "all"; int theme = 0;
  static final int[][] THEMES = {{0xFFFFFF,0x000000,0x2E7D32},{0x101820,0xE0E0E0,0x00B0FF},{0xFFF3E0,0x3E2723,0xEF6C00}};
  List menu = new List("MeshChat", List.IMPLICIT);
  Command ok = new Command("OK", Command.OK, 1), back = new Command("Back", Command.BACK, 2), exit = new Command("Exit", Command.EXIT, 9);
  ChatCanvas chat; DiscoveryAgent agent; List devList;

  public void startApp() {
    if (chat != null) return;
    chat = new ChatCanvas(this); d = Display.getDisplay(this);
    menu.append("Open chat", null); menu.append("Find nearby (Bluetooth)", null);
    menu.append("Connect (mobile data)", null); menu.append("Set name / group", null);
    menu.append("Theme", null); menu.setCommandListener(this); menu.addCommand(exit);
    d.setCurrent(menu); startBtServer();
  }
  public void pauseApp() {} public void destroyApp(boolean u) {}

  public void commandAction(Command c, Displayable s) {
    if (c == exit) { notifyDestroyed(); return; }
    if (c == back) { d.setCurrent(menu); return; }
    if (s == menu) switch (menu.getSelectedIndex()) {
      case 0: d.setCurrent(chat); break;
      case 1: scan(); break;
      case 2: ask("Relay host:port", "example.com:9000"); break;
      case 3: ask("Name|group", me + "|" + group); break;
      case 4: theme = (theme + 1) % THEMES.length; chat.repaint(); d.setCurrent(chat); break;
    } else if (s == devList) { if (devs.size() > 0) connectBt((RemoteDevice) devs.elementAt(devList.getSelectedIndex())); }
    else if (s instanceof TextBox) {
      String t = ((TextBox) s).getString(), ti = s.getTitle();
      if (c == ok) { if (ti.startsWith("Relay")) connectRelay(t);
        else if (ti.startsWith("Name")) { int i = t.indexOf('|'); me = i < 0 ? t : t.substring(0, i); if (i >= 0) group = t.substring(i + 1); }
        else sendMsg(t); }
      d.setCurrent(ti.equals("Message") ? (Displayable) chat : menu);
    }
  }
  void ask(String title, String init) { TextBox t = new TextBox(title, init, 120, TextField.ANY); t.addCommand(ok); t.addCommand(back); t.setCommandListener(this); d.setCurrent(t); }
  void compose() { ask("Message", ""); }
  void note(String s) { log.addElement("* " + s); if (log.size() > 60) log.removeElementAt(0); chat.repaint(); }

  void sendMsg(String text) {
    if (text.length() == 0) return;
    String id = me + System.currentTimeMillis(); seen.addElement(id);
    log.addElement("me: " + text); chat.repaint();
    flood("M|" + id + "|" + me + "|" + group + "|" + text, null);
  }
  void flood(String line, Link except) { for (int i = 0; i < links.size(); i++) { Link l = (Link) links.elementAt(i); if (l != except) l.send(line); } }
  void onLine(Link from, String line) {
    Vector p = split(line, '|', 5); if (p.size() < 5 || !"M".equals(p.elementAt(0))) return;
    String id = (String) p.elementAt(1); if (seen.contains(id)) return;
    seen.addElement(id); if (seen.size() > 200) seen.removeElementAt(0);
    flood(line, from);
    if (p.elementAt(3).equals(group) || p.elementAt(3).equals("all")) {
      log.addElement(p.elementAt(2) + ": " + p.elementAt(4)); chat.repaint();
      AlertType.ALARM.playSound(d); d.vibrate(300);
    }
  }
  static Vector split(String s, char c, int max) { Vector v = new Vector(); int a = 0, i; while (v.size() < max - 1 && (i = s.indexOf(c, a)) >= 0) { v.addElement(s.substring(a, i)); a = i + 1; } v.addElement(s.substring(a)); return v; }

  void startBtServer() { new Thread() { public void run() { try {
    LocalDevice.getLocalDevice().setDiscoverable(DiscoveryAgent.GIAC);
    StreamConnectionNotifier n = (StreamConnectionNotifier) Connector.open("btspp://localhost:" + UUID_STR + ";name=MeshChat");
    while (true) { StreamConnection c = n.acceptAndOpen(); addLink(c.openInputStream(), c.openOutputStream(), "BT in"); }
  } catch (Exception e) { note("BT server: " + e); } } }.start(); }
  void scan() { try { devs.removeAllElements(); devList = new List("Nearby devices", List.IMPLICIT); devList.addCommand(back); devList.setCommandListener(this);
    d.setCurrent(devList); agent = LocalDevice.getLocalDevice().getDiscoveryAgent(); agent.startInquiry(DiscoveryAgent.GIAC, this); } catch (Exception e) { note("scan: " + e); } }
  public void deviceDiscovered(RemoteDevice r, DeviceClass c) { devs.addElement(r); String n; try { n = r.getFriendlyName(false); } catch (Exception e) { n = r.getBluetoothAddress(); } devList.append(n, null); }
  public void inquiryCompleted(int t) { if (devs.size() == 0) devList.append("(none found)", null); }
  void connectBt(RemoteDevice r) { try { agent.searchServices(null, new UUID[]{new UUID(UUID_STR, false)}, r, this); } catch (Exception e) { note("svc: " + e); } }
  public void servicesDiscovered(int tid, ServiceRecord[] s) { final String url = s[0].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
    new Thread() { public void run() { try { StreamConnection c = (StreamConnection) Connector.open(url); addLink(c.openInputStream(), c.openOutputStream(), "BT out"); d.setCurrent(chat); } catch (Exception e) { note("connect: " + e); } } }.start(); }
  public void serviceSearchCompleted(int t, int r) {}

  void connectRelay(final String hp) { new Thread() { public void run() { try {
    StreamConnection c = (StreamConnection) Connector.open("socket://" + hp);
    addLink(c.openInputStream(), c.openOutputStream(), "relay"); } catch (Exception e) { note("relay: " + e); } } }.start(); }

  void addLink(InputStream in, OutputStream out, String name) { Link l = new Link(this, in, out); links.addElement(l); note("linked: " + name); new Thread(l).start(); }

  static class Link implements Runnable {
    MeshMidlet a; InputStream in; OutputStream out;
    Link(MeshMidlet a, InputStream i, OutputStream o) { this.a = a; in = i; out = o; }
    synchronized void send(String s) { try { out.write((s + "\n").getBytes("UTF-8")); out.flush(); } catch (Exception e) {} }
    public void run() { try { ByteArrayOutputStream b = new ByteArrayOutputStream(); int x;
      while ((x = in.read()) >= 0) { if (x == '\n') { a.onLine(this, new String(b.toByteArray(), "UTF-8")); b.reset(); } else b.write(x); }
    } catch (Exception e) {} a.links.removeElement(this); a.note("link lost"); }
  }
}

class ChatCanvas extends Canvas {
  MeshMidlet m; ChatCanvas(MeshMidlet m) { this.m = m; }
  protected void paint(Graphics g) {
    int[] t = MeshMidlet.THEMES[m.theme]; int w = getWidth(), h = getHeight();
    g.setColor(t[0]); g.fillRect(0, 0, w, h);
    Font f = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL); g.setFont(f);
    g.setColor(t[2]); g.fillRect(0, 0, w, f.getHeight() + 2); g.setColor(0xFFFFFF);
    g.drawString(m.group + " links:" + m.links.size() + " 5=type *=menu", 2, 1, Graphics.TOP | Graphics.LEFT);
    int y = h - f.getHeight() - 2; g.setColor(t[1]);
    for (int i = m.log.size() - 1; i >= 0 && y > f.getHeight() + 2; i--) { g.drawString((String) m.log.elementAt(i), 2, y, Graphics.TOP | Graphics.LEFT); y -= f.getHeight() + 2; }
  }
  protected void keyPressed(int k) {
    if (k == KEY_STAR || k == -7) m.d.setCurrent(m.menu);
    else if (getGameAction(k) == FIRE || k == KEY_NUM5 || k == -5) m.compose();
  }
      }
