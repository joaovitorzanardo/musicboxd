package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;

/** The catalog routes ride the springdoc pipeline (AD-7), and none of them deletes (AD-12). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogContractTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void catalogRoutesAreInTheContractAndNothingDeletes() throws Exception {
		String docs = mockMvc.perform(get("/api/v1/api-docs")).andReturn().getResponse().getContentAsString();
		Map<String, Map<String, Object>> paths = JsonPath.read(docs, "$.paths");

		assertThat(paths.get("/api/v1/admin/catalog/albums")).containsKey("post");
		assertThat(paths.get("/api/v1/admin/catalog/albums/{id}")).containsKeys("get", "put");
		assertThat(paths.get("/api/v1/admin/catalog/albums/{id}/hidden")).containsKey("put");
		assertThat(paths.get("/api/v1/admin/catalog/songs/{id}/hidden")).containsKey("put");
		assertThat(paths.get("/api/v1/admin/catalog/genres")).containsKey("post");
		assertThat(paths.get("/api/v1/admin/catalog/genres/{id}")).containsKey("patch");
		assertThat(paths.get("/api/v1/catalog/albums/{id}")).containsKey("get");
		assertThat(paths.get("/api/v1/catalog/songs/{id}")).containsKey("get");
		paths.forEach((path, operations) -> {
			if (path.contains("/catalog") || path.contains("/albums") || path.contains("/songs") || path.contains("/genres")) {
				assertThat(operations).as("DELETE on %s (AD-12)", path).doesNotContainKey("delete");
			}
		});
	}
}
