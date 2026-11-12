#ifndef CHAT_H
#define CHAT_H
#include <QtGui>
#include <QtNetwork>
#include <qbluetoothsocket.h>
#include <qrfcommserver.h>
#include <qbluetoothaddress.h>
#include <qbluetoothuuid.h>
#include <qbluetoothserviceinfo.h>
#include <qbluetoothdevicediscoveryagent.h>
#include <qbluetoothdeviceinfo.h>
QTM_USE_NAMESPACE

class Link : public QObject {
    Q_OBJECT
public:
    Link(QIODevice *d, QObject *p) : QObject(p), dev(d) {
        connect(d, SIGNAL(readyRead()), this, SLOT(onRead()));
        connect(d, SIGNAL(disconnected()), this, SLOT(onLost()));
    }
    void send(const QString &s) { dev->write(s.toUtf8() + "\n"); }
signals:
    void line(Link *, const QString &);
    void lost(Link *);
private slots:
    void onRead() {
        buf += dev->readAll(); int i;
        while ((i = buf.indexOf('\n')) >= 0) { QString l = QString::fromUtf8(buf.left(i)); buf.remove(0, i + 1); emit line(this, l); }
    }
    void onLost() { emit lost(this); }
private:
    QIODevice *dev; QByteArray buf;
};

class Chat : public QWidget {
    Q_OBJECT
public:
    Chat(QWidget *parent = 0);
private slots:
    void send();
    void nearby();
    void deviceFound(const QBluetoothDeviceInfo &);
    void devicePicked(QListWidgetItem *);
    void connectRelay();
    void nameGroup();
    void nextTheme();
    void btIn();
    void tcpIn();
    void onLine(Link *, const QString &);
    void onLost(Link *);
private:
    void addLink(QIODevice *d, const QString &name);
    void note(const QString &s);
    void flood(const QString &l, Link *except);
    void applyTheme();
    void startServers();
    QTextEdit *log; QLineEdit *input; QLabel *status;
    QList<Link *> links; QStringList seen;
    QString me, group; int theme;
    QRfcommServer *btServer; QTcpServer *tcpServer;
    QBluetoothDeviceDiscoveryAgent *agent; QListWidget *devList; QList<QBluetoothAddress> addrs; QDialog *dlg;
};
#endif
