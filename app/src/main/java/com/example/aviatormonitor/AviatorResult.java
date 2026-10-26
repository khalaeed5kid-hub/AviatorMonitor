package com.example.aviatormonitor;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "aviator_results")
public class AviatorResult {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public double multiplier;
    public String timestamp;
    public int roundNumber;
    public boolean isConfirmed;

    public AviatorResult(double multiplier, String timestamp, int roundNumber, boolean isConfirmed) {
        this.multiplier = multiplier;
        this.timestamp = timestamp;
        this.roundNumber = roundNumber;
        this.isConfirmed = isConfirmed;
    }
}
