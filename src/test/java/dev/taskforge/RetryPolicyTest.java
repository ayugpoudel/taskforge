package dev.taskforge;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.assertj.core.api.Assertions.*;
class RetryPolicyTest {
    @Test void equalJitterIsBoundedAndExponentiallyCapped() {
        var p=new RetryPolicy();var random=new Random(1234);
        for(int attempt=1;attempt<100;attempt++) {
            long ceiling=Math.min(60_000,1000L<<Math.min(6,attempt-1));
            for(int i=0;i<30;i++)assertThat(p.delay(attempt,random).toMillis()).isBetween(ceiling/2,ceiling);
        }
    }
}
