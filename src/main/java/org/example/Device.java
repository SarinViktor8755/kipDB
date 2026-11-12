package org.example;

public class Device {
    public long id;
    public String serialNumber;
    public String name;
    public String location;
    public String lastVerification;
    public String nextVerification;
    public String photoPath;
    public String photoThumbPath;
    public int version = 0;
    public String createdAt;

    public Device() {
    }

    public Device(long id, String serialNumber, String name, String location,
                  String lastVerification, String nextVerification,
                  String photoPath, String photoThumbPath, int version, String createdAt) {
        this.id = id;
        this.serialNumber = serialNumber;
        this.name = name;
        this.location = location;
        this.lastVerification = lastVerification;
        this.nextVerification = nextVerification;
        this.photoPath = photoPath;
        this.photoThumbPath = photoThumbPath;
        this.version = version;
        this.createdAt = createdAt;
    }
}
