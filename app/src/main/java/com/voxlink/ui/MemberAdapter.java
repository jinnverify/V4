package com.voxlink.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.voxlink.model.Room;

import java.util.List;

public class MemberAdapter extends ArrayAdapter<Room.Member> {

    public MemberAdapter(Context context, List<Room.Member> members) {
        super(context, android.R.layout.simple_list_item_1, members);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        }
        Room.Member member = getItem(position);
        TextView tv = convertView.findViewById(android.R.id.text1);
        if (member != null) {
            String icon = member.isMuted ? "🔇" : (member.isSpeaking ? "🔊" : "🎙");
            tv.setText(icon + "  " + member.name);
            tv.setTextColor(member.isSpeaking ? Color.parseColor("#4ADE80") : Color.parseColor("#DDDDDD"));
            tv.setTextSize(15f);
            tv.setPadding(32, 18, 32, 18);
            tv.setBackgroundColor(Color.parseColor("#111111"));
        }
        return convertView;
    }
}
