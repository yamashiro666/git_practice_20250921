package git_practice_20250921;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import test.InputDto;
import test.StreamWithRetry;

public class StreamWithRetryTest {
	
	@Test
	public void test_1() throws Exception {
		
		// DTO作成
		InputDto dto = new InputDto();
		
		// クエリストリングを設定
        dto.setOpt(List.of(
                Map.of("key1", "value1"),
                Map.of("key2", "value2"),
                Map.of("key3", "value3"),
                Map.of("key4", "value4")
            ));
        
        // 呼び出し
		StreamWithRetry st = new StreamWithRetry();
		
		try {
			st.runStream(dto);			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
	
}
