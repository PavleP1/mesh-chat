#include <QApplication>
#include "chat.h"
int main(int argc, char *argv[]) {
    QApplication a(argc, argv);
    Chat w;
    w.showFullScreen();
    return a.exec();
}
