package com.salesmanager.shop.store.controller.search;

import com.salesmanager.core.business.services.catalog.category.CategoryService;
import com.salesmanager.core.business.services.catalog.product.PricingService;
import com.salesmanager.core.business.services.catalog.product.ProductService;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.reference.language.LanguageService;
import com.salesmanager.core.business.services.search.SearchService;
import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.ProductCriteria;
import com.salesmanager.core.model.catalog.product.ProductList;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.search.*;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.model.catalog.SearchProductList;
import com.salesmanager.shop.model.catalog.category.ReadableCategory;
import com.salesmanager.shop.model.catalog.product.ReadableProduct;
import com.salesmanager.shop.populator.catalog.ReadableCategoryPopulator;
import com.salesmanager.shop.populator.catalog.ReadableProductPopulator;
import com.salesmanager.shop.store.controller.ControllerConstants;
import com.salesmanager.shop.store.model.search.AutoCompleteRequest;
import com.salesmanager.shop.utils.ImageFilePath;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.StringWriter;
import java.util.*;

@Controller
public class SearchController {
	
	@Inject
	private MerchantStoreService merchantStoreService;
	
	@Inject
	private LanguageService languageService;
	
	@Inject
	private SearchService searchService;
	
	@Inject
	private ProductService productService;
	
	@Inject
	private CategoryService categoryService;
	
	@Inject
	private PricingService pricingService;
	
	@Inject
	@Qualifier("img")
	private ImageFilePath imageUtils;

	
	private static final Logger LOGGER = LoggerFactory.getLogger(SearchController.class);
	
	private final static int AUTOCOMPLETE_ENTRIES_COUNT = 15;
	private final static String CATEGORY_FACET_NAME = "categories";
	private final static String MANUFACTURER_FACET_NAME = "manufacturer";
	
	
	/**
	 * Retrieves a list of keywords for a given series of character typed by the end user
	 * This is used for auto complete on search input field
	 * @param json
	 * @param store
	 * @param language
	 * @param model
	 * @param request
	 * @param response
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value="/services/public/search/{store}/{language}/autocomplete.json", produces="application/json;charset=UTF-8")
	@ResponseBody
	public String autocomplete(@RequestParam("q") String query, @PathVariable String store, @PathVariable final String language, Model model, HttpServletRequest request, HttpServletResponse response)  {

		MerchantStore merchantStore = (MerchantStore)request.getAttribute(Constants.MERCHANT_STORE);

		if(merchantStore!=null) {
			if(!merchantStore.getCode().equals(store)) {
				merchantStore = null; //reset for the current request
			}
		}
		
		try {
		
			if(merchantStore== null) {
					merchantStore = merchantStoreService.getByCode(store);
			}
			
			if(merchantStore==null) {
				LOGGER.error("Merchant store is null for code " + store);
				response.sendError(503, "Merchant store is null for code " + store);//TODO localized message
				return null;
			}
			
			AutoCompleteRequest req = new AutoCompleteRequest(store,language);
			/** formatted toJSONString because of te specific field names required in the UI **/
			SearchKeywords keywords = searchService.searchForKeywords(req.getCollectionName(), req.toJSONString(query), AUTOCOMPLETE_ENTRIES_COUNT);
			return keywords.toJSONString();

			
		} catch (Exception e) {
			LOGGER.error("Exception while autocomplete " + e);
		}
		
		return null;
		
	}

	
	/**
	 * Displays the search result page
	 * @param json
	 * @param store
	 * @param language
	 * @param start
	 * @param max
	 * @param model
	 * @param request
	 * @param response
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value="/services/public/search/{store}/{language}/{start}/{max}/search.json", method=RequestMethod.POST)
	@ResponseBody
	//public SearchProductList search(@RequestBody String json, @PathVariable String store, @PathVariable final String language, @PathVariable int start, @PathVariable int max, Model model, HttpServletRequest request, HttpServletResponse response) {
	public SearchProductList search(@PathVariable String store, @PathVariable final String language, @PathVariable int start, @PathVariable int max, Model model, HttpServletRequest request, HttpServletResponse response) {
		SearchProductList returnList = new SearchProductList();
		MerchantStore merchantStore = (MerchantStore)request.getAttribute(Constants.MERCHANT_STORE);
		
		String json = null;
		
		try {
			
			StringWriter writer = new StringWriter();
			IOUtils.copy(request.getInputStream(), writer, "UTF-8");
			json = writer.toString();
			
			Map<String,Language> langs = languageService.getLanguagesMap();
			
			if(merchantStore!=null) {
				if(!merchantStore.getCode().equals(store)) {
					merchantStore = null; //reset for the current request
				}
			}
			
			if(merchantStore== null) {
				merchantStore = merchantStoreService.getByCode(store);
			}
			
			if(merchantStore==null) {
				LOGGER.error("Merchant store is null for code " + store);
				response.sendError(503, "Merchant store is null for code " + store);//TODO localized message
				return null;
			}
			
			Language l = langs.get(language);
			if(l==null) {
				l = languageService.getByCode(Constants.DEFAULT_LANGUAGE);
			}

			SearchResponse resp = searchService.search(merchantStore, language, json, max, start);
			
			List<SearchEntry> entries = resp.getEntries();
			
			if(!CollectionUtils.isEmpty(entries)) {
				List<Long> ids = new ArrayList<Long>();
				for(SearchEntry entry : entries) {
					IndexProduct indexedProduct = entry.getIndexProduct();
					Long id = Long.parseLong(indexedProduct.getId());
					
					//No highlights	
					ids.add(id);
				}
				
				ProductCriteria searchCriteria = new ProductCriteria();
				searchCriteria.setMaxCount(max);
				searchCriteria.setStartIndex(start);
				searchCriteria.setProductIds(ids);
				searchCriteria.setAvailable(true);
				
				ProductList productList = productService.listByStore(merchantStore, l, searchCriteria);
				
				ReadableProductPopulator populator = new ReadableProductPopulator();
				populator.setPricingService(pricingService);
				populator.setimageUtils(imageUtils);
				
				for(Product product : productList.getProducts()) {
					//create new proxy product
					ReadableProduct p = populator.populate(product, new ReadableProduct(), merchantStore, l);
					
					//com.salesmanager.web.entity.catalog.Product p = catalogUtils.buildProxyProduct(product,merchantStore,LocaleUtils.getLocale(l));
					returnList.getProducts().add(p);
		
				}
				returnList.setProductCount(productList.getProducts().size());
			}
			
			//Facets
			Map<String,List<SearchFacet>> facets = resp.getFacets();
			List<SearchFacet> categoriesFacets = null;
			List<SearchFacet> manufacturersFacets = null;
			if(facets!=null) {
				for(String key : facets.keySet()) {
					//supports category and manufacturer
					if(CATEGORY_FACET_NAME.equals(key)) {
						categoriesFacets = facets.get(key);
					}
					
					if(MANUFACTURER_FACET_NAME.equals(key)) {
						manufacturersFacets = facets.get(key);
					}
				}
				
				
				if(categoriesFacets!=null) {
					List<String> categoryCodes = new ArrayList<String>();
					Map<String,Long> productCategoryCount = new HashMap<String,Long>();
					for(SearchFacet facet : categoriesFacets) {
						categoryCodes.add(facet.getName());
						productCategoryCount.put(facet.getKey(), facet.getCount());
					}
					
					List<Category> categories = categoryService.listByCodes(merchantStore, categoryCodes, l);
					List<ReadableCategory> categoryProxies = new ArrayList<ReadableCategory>();
					ReadableCategoryPopulator populator = new ReadableCategoryPopulator();
					
					for(Category category : categories) {
						//com.salesmanager.web.entity.catalog.Category categoryProxy = catalogUtils.buildProxyCategory(category, merchantStore, LocaleUtils.getLocale(l));
						ReadableCategory categoryProxy = populator.populate(category, new ReadableCategory(), merchantStore, l);
						Long total = productCategoryCount.get(categoryProxy.getCode());
						if(total!=null) {
							categoryProxy.setProductCount(total.intValue());
						}
						categoryProxies.add(categoryProxy);
					}
					returnList.setCategoryFacets(categoryProxies);
				}
				
				//todo manufacturer facets
				if(manufacturersFacets!=null) {
					
				}
				
				
			}
		} catch (Exception e) {
			LOGGER.error("Exception occured while querying " + json,e);
		}
		

		
		return returnList;
		
	}
	
	/**
	 * Displays the search page after a search query post
	 * @param query
	 * @param model
	 * @param request
	 * @param response
	 * @param locale
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value={"/shop/search/search.html"}, method=RequestMethod.POST)
	public String displaySearch(@RequestParam("q") String query, Model model, HttpServletRequest request, HttpServletResponse response, Locale locale) throws Exception {

		MerchantStore store = (MerchantStore)request.getAttribute(Constants.MERCHANT_STORE);

		model.addAttribute("q",query);
		
		/** template **/
		StringBuilder template = new StringBuilder().append(ControllerConstants.Tiles.Search.search).append(".").append(store.getStoreTemplate());
		return template.toString();
	}
	
	
}
