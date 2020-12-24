package load.datapool.todo.rest;

import load.datapool.prometheus.Exporter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/")
public class RootController {
    @GetMapping(path = "/")
    public ModelAndView getIndex(){
        return new ModelAndView("index.html");
    }
    @GetMapping(path = "/about")
    public ModelAndView getAbout(){
        return getIndex();
    }

    @GetMapping(path = "/metrics")
    public ResponseEntity<String>  getMetrics(@RequestParam(value = "calculateUnlocks", defaultValue = "false") boolean calculateUnlocks) throws IOException {
        Exporter exp = new Exporter();
        exp.isCalcAVRows = calculateUnlocks;
        ResponseEntity<String> res =
                ResponseEntity.ok()
                        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                        .body(exp.getMetrics());
        return res;
    }
}
