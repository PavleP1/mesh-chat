QT += core gui network
CONFIG += mobility
MOBILITY = connectivity
TARGET = MeshChat
TEMPLATE = app
SOURCES += main.cpp chat.cpp
HEADERS += chat.h
symbian {
    TARGET.UID3 = 0xE0A1B2C3
    TARGET.CAPABILITY += NetworkServices LocalServices ReadUserData WriteUserData UserEnvironment
    TARGET.EPOCHEAPSIZE = 0x20000 0x2000000
    DEPLOYMENT.display_name = MeshChat
}
