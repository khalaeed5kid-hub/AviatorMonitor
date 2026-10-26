package com.example.aviatormonitor;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ResultDao {
    @Insert
    void insert(AviatorResult result);

    @Query("SELECT * FROM aviator_results ORDER BY id DESC")
    List<AviatorResult> getAllResults();

    @Query("DELETE FROM aviator_results")
    void deleteAll();
}
