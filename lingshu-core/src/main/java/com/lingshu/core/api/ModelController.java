package com.lingshu.core.api;

import com.lingshu.core.provider.ModelProviderRouter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/models")
public class ModelController {

    private final ModelProviderRouter router;

    public ModelController(ModelProviderRouter router) {
        this.router = router;
    }

    @GetMapping
    public ModelListResponse listModels() {
        List<ModelItem> models = router.availableModels().stream()
                .map(model -> new ModelItem(
                        model.id(),
                        "model",
                        model.provider(),
                        model.status().name()
                ))
                .toList();
        return new ModelListResponse("list", models);
    }

    public record ModelListResponse(String object, List<ModelItem> data) {
    }

    public record ModelItem(String id, String object, String provider, String status) {
    }
}
