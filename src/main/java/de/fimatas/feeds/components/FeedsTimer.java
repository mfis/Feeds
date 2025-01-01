package de.fimatas.feeds.components;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

public class FeedsTimer {

    public ZonedDateTime zonedDateTimeNow(){
        return ZonedDateTime.now();
    }

    public LocalDateTime localDateTimeNow(){
        return LocalDateTime.now();
    }

    public LocalTime localTimeNow(){
        return LocalTime.now();
    }
}
