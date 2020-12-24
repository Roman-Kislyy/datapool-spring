package load.datapool.todo.rest;

import load.datapool.prometheus.Exporter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

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
    public String getMetrics(@RequestParam(value = "calculateUnlocks", defaultValue = "false") boolean calculateUnlocks) throws IOException {
        Exporter exp = new Exporter();
        exp.isCalcAVRows = calculateUnlocks;
        return exp.getMetrics();
    }
}
