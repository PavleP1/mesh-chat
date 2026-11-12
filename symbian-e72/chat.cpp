#include "chat.h"
static const char *UUID_STR = "A1B2C3D4-E5F6-0718-293A-4B5C6D7E8F90";

Chat::Chat(QWidget *p) : QWidget(p), theme(0), btServer(0), tcpServer(0), agent(0), devList(0), dlg(0) {
    me = "E72-" + QString::number(qrand() % 900 + 100); group = "all";
    status = new QLabel; log = new QTextEdit; log->setReadOnly(true); input = new QLineEdit;
    QPushButton *bSend = new QPushButton("Send"), *bNear = new QPushButton("Nearby"),
                *bRel = new QPushButton("Wi-Fi/Net"), *bName = new QPushButton("Name"), *bTheme = new QPushButton("Theme");
    QHBoxLayout *r1 = new QHBoxLayout; r1->addWidget(input, 1); r1->addWidget(bSend);
    QHBoxLayout *r2 = new QHBoxLayout; r2->addWidget(bNear); r2->addWidget(bRel); r2->addWidget(bName); r2->addWidget(bTheme);
    QVBoxLayout *v = new QVBoxLayout(this); v->setContentsMargins(2, 2, 2, 2);
    v->addWidget(status); v->addWidget(log, 1); v->addLayout(r1); v->addLayout(r2);
    connect(bSend, SIGNAL(clicked()), this, SLOT(send())); connect(input, SIGNAL(returnPressed()), this, SLOT(send()));
    connect(bNear, SIGNAL(clicked()), this, SLOT(nearby())); connect(bRel, SIGNAL(clicked()), this, SLOT(connectRelay()));
    connect(bName, SIGNAL(clicked()), this, SLOT(nameGroup())); connect(bTheme, SIGNAL(clicked()), this, SLOT(nextTheme()));
    applyTheme(); startServers(); note("Ready. Make Bluetooth visible.");
}

void Chat::startServers() {
    btServer = new QRfcommServer(this);
    connect(btServer, SIGNAL(newConnection()), this, SLOT(btIn()));
    if (btServer->listen(QBluetoothAddress())) {
        QBluetoothServiceInfo info;
        info.setAttribute(QBluetoothServiceInfo::ServiceName, "MeshChat");
        info.setAttribute(QBluetoothServiceInfo::BrowseGroupList, QVariant::fromValue(QBluetoothUuid(QBluetoothUuid::PublicBrowseGroup)));
        QBluetoothServiceInfo::Sequence cls; cls << QVariant::fromValue(QBluetoothUuid(QString(UUID_STR)));
        info.setAttribute(QBluetoothServiceInfo::ServiceClassIds, cls);
        QBluetoothServiceInfo::Sequence proto, l2, rf;
        l2 << QVariant::fromValue(QBluetoothUuid(QBluetoothUuid::L2cap)); proto.append(QVariant::fromValue(l2));
        rf << QVariant::fromValue(QBluetoothUuid(QBluetoothUuid::Rfcomm)) << QVariant::fromValue(quint8(btServer->serverPort()));
        proto.append(QVariant::fromValue(rf));
        info.setAttribute(QBluetoothServiceInfo::ProtocolDescriptorList, proto);
        info.registerService();
    } else note("BT server failed");
    tcpServer = new QTcpServer(this);
    connect(tcpServer, SIGNAL(newConnection()), this, SLOT(tcpIn()));
    tcpServer->listen(QHostAddress::Any, 9001);
}
void Chat::btIn()  { while (btServer->hasPendingConnections()) addLink(btServer->nextPendingConnection(), "BT in"); }
void Chat::tcpIn() { while (tcpServer->hasPendingConnections()) addLink(tcpServer->nextPendingConnection(), "TCP in"); }

void Chat::addLink(QIODevice *d, const QString &name) {
    Link *l = new Link(d, this); links << l;
    connect(l, SIGNAL(line(Link *, const QString &)), this, SLOT(onLine(Link *, const QString &)));
    connect(l, SIGNAL(lost(Link *)), this, SLOT(onLost(Link *)));
    note("linked: " + name);
}
void Chat::onLost(Link *l) { links.removeAll(l); note("link lost"); }

void Chat::send() {
    QString t = input->text().replace('\n', ' '); if (t.isEmpty()) return;
    QString id = me + QString::number(QDateTime::currentMSecsSinceEpoch()); seen << id; input->clear();
    note("me: " + t); flood("M|" + id + "|" + me + "|" + group + "|" + t, 0);
}
void Chat::flood(const QString &l, Link *except) { foreach (Link *k, links) if (k != except) k->send(l); }
void Chat::onLine(Link *from, const QString &line) {
    QStringList p = line.split('|'); if (p.size() < 5 || p[0] != "M" || seen.contains(p[1])) return;
    seen << p[1]; if (seen.size() > 300) seen.removeFirst();
    flood(line, from);
    QString text = QStringList(p.mid(4)).join("|");
    if (p[3] == group || p[3] == "all") { note(p[2] + ": " + text); QApplication::beep(); }
}

void Chat::nearby() {
    addrs.clear(); dlg = new QDialog(this); dlg->setWindowTitle("Nearby (visible BT)");
    devList = new QListWidget; QVBoxLayout *v = new QVBoxLayout(dlg); v->addWidget(devList);
    connect(devList, SIGNAL(itemClicked(QListWidgetItem *)), this, SLOT(devicePicked(QListWidgetItem *)));
    agent = new QBluetoothDeviceDiscoveryAgent(this);
    connect(agent, SIGNAL(deviceDiscovered(const QBluetoothDeviceInfo &)), this, SLOT(deviceFound(const QBluetoothDeviceInfo &)));
    agent->start(); dlg->showMaximized();
}
void Chat::deviceFound(const QBluetoothDeviceInfo &i) { addrs << i.address(); devList->addItem(i.name() + " " + i.address().toString()); }
void Chat::devicePicked(QListWidgetItem *) {
    int r = devList->currentRow(); if (r < 0 || r >= addrs.size()) return;
    agent->stop();
    QBluetoothSocket *s = new QBluetoothSocket(QBluetoothSocket::RfcommSocket, this);
    s->connectToService(addrs[r], QBluetoothUuid(QString(UUID_STR)));
    addLink(s, "BT out"); dlg->close();
}
void Chat::connectRelay() {
    QString hp = QInputDialog::getText(this, "host:port", "Relay :9000 or phone :9001", QLineEdit::Normal, "192.168.1.10:9000");
    if (hp.isEmpty()) return;
    QStringList p = hp.split(':'); if (p.size() != 2) return;
    QTcpSocket *s = new QTcpSocket(this); s->connectToHost(p[0], p[1].toUInt()); addLink(s, "TCP " + hp);
}
void Chat::nameGroup() {
    QString t = QInputDialog::getText(this, "name|group", "Name|Group", QLineEdit::Normal, me + "|" + group);
    QStringList p = t.split('|'); if (!p[0].isEmpty()) me = p[0]; if (p.size() > 1) group = p[1];
}
void Chat::nextTheme() { theme = (theme + 1) % 3; applyTheme(); }
void Chat::applyTheme() {
    static const char *bg[] = {"#FFFFFF", "#101820", "#FFF3E0"}, *fg[] = {"#000000", "#E0E0E0", "#3E2723"};
    setStyleSheet(QString("QWidget{background:%1;color:%2;font-size:12px}").arg(bg[theme]).arg(fg[theme]));
}
void Chat::note(const QString &s) { log->append(s); status->setText(me + " | " + group + " | links: " + QString::number(links.size())); }
