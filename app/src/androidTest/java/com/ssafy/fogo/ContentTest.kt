package com.ssafy.fogo

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentTest {
    @Test fun particleFollowsTheLastSyllable() {
        assertEquals("안개 구름과", "안개 구름".particle("과", "와"))
        assertEquals("오리와", "오리".particle("과", "와"))
        assertEquals("평창 스티커를", "평창 스티커".particle("을", "를"))
        assertEquals("강원 자석을", "강원 자석".particle("을", "를"))
        assertEquals("그랜저로", "그랜저".particle("으로", "로"))
        assertEquals("서울로", "서울".particle("으로", "로"))
        assertEquals("부산으로", "부산".particle("으로", "로"))
        assertEquals("G80로", "G80".particle("으로", "로"))
    }
}
