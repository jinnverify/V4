package com.voxlink.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a voice room
 */
public class Room {
    public String roomId;
    public String password;
    public String hostName;
    public int memberCount;
    public List<Member> members;

    public Room(String roomId, String password) {
        this.roomId = roomId;
        this.password = password;
        this.members = new ArrayList<>();
    }

    public static class Member {
        public String userId;
        public String name;
        public boolean isMuted;
        public boolean isSpeaking;

        public Member(String userId, String name) {
            this.userId = userId;
            this.name = name;
            this.isMuted = false;
            this.isSpeaking = false;
        }
    }
}
