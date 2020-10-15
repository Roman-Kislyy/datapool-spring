package load.datapool.todo.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
@RequestMapping("/")
public class RootController {
    @GetMapping(path = "/")
    public ModelAndView getIndex(){

        //return ResponseEntity.ok("html");
        return new ModelAndView("index.html");
    }
    @GetMapping(path = "/about")
    public ModelAndView getAbout(){

        //return ResponseEntity.ok("html");
        return getIndex();
    }
}
