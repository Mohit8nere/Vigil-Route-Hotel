package com.vigil.android.presentation.feature_attendance.patroling_ui;

import android.graphics.Bitmap;

public class Person {

    public String name;
    public Bitmap face;
    public byte[] templates;

    public Person() {

    }

    public Person(String name, Bitmap face, byte[] templates) {
        this.name = name;
        this.face = face;
        this.templates = templates;
    }
}
