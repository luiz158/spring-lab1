package com.example;

import static com.example.ReservationsRepository.Spec.*;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.*;
import static org.springframework.transaction.annotation.Propagation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.stream.Stream;

import com.querydsl.core.BooleanBuilder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class ReservationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReservationServiceApplication.class, args);
	}

	@Bean
	public HealthIndicator reservationsHealth() {
		return () -> Health.status("This app is UP").build();
	}
}

@RestController
@RequestMapping("/custom-reservations")
class ReservationsController {

	private final ReservationsService reservations;

	public ReservationsController(ReservationsService reservations) {
		this.reservations = reservations;
	}

	@GetMapping(produces = APPLICATION_JSON_VALUE)
	Page<Reservation> list(
			@RequestParam(name = "name", required = false) String name,
			@RequestParam(name = "lang", required = false) String lang,
			Pageable pageable) {
		return reservations.findAll(name, lang, pageable);
	}

	@PostMapping(consumes = APPLICATION_JSON_VALUE)
	@ResponseStatus(CREATED)
	void create(@RequestBody Reservation reservation) {
		reservations.create(reservation);
	}

	@GetMapping(path = "/{id}", produces = APPLICATION_JSON_VALUE)
	ResponseEntity<?> get(@PathVariable("id") Long id) {
		Optional<Reservation> reservation = reservations.findOne(id);
		if (reservation.isPresent()) {
			return ResponseEntity.ok(reservation.get());
		} else {
			return ResponseEntity.status(NOT_FOUND).build();
		}
	}

	@PutMapping(path = "/{id}", consumes = APPLICATION_JSON_VALUE)
	void update(@PathVariable("id") Long id, @RequestBody Reservation reservation) {
		reservations.update(id, reservation);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(NO_CONTENT)
	void delete(@PathVariable("id") Long id) {
		reservations.delete(id);
	}

	@ExceptionHandler(ReservationNotFound.class)
	void handleReservationNotFound(ReservationNotFound ex, HttpServletResponse response) throws IOException {
		response.sendError(NOT_FOUND.value(), ex.getMessage());
	}
}

@Slf4j
@Configuration
@EnableAspectJAutoProxy
class ServiceConfig {

	@Bean
	ReservationsService reservationsService(ReservationsRepository repository) {
		ReservationsService target = new ReservationsServiceImpl(repository);
		return (ReservationsService) Proxy.newProxyInstance(
			Thread.currentThread().getContextClassLoader(),
			new Class[] { ReservationsService.class },
			(Object proxy, Method method, Object[] args) -> {
				log.info("BEFORE method {}", method.getName());
				Object result = method.invoke(target, args);
				log.info("AFTER method {}. RETURNED {}", method.getName(), result);
				return result;
			}
		);
	}
}

@Slf4j
@Aspect
@Component
class MonitorAspect {

	@Pointcut("execution(* com.example.ReservationsService.*(..))")
	private void anyServiceOperation() {}

	@Around("anyServiceOperation()")
	public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
		String name = joinPoint.getSignature().getName();
		StopWatch stopWatch = new StopWatch();
		stopWatch.start(name);
		Object result = joinPoint.proceed();
		stopWatch.stop();
		log.info("MONITOR method {} took {}ms", name, stopWatch.getLastTaskInfo().getTimeMillis());
		return result;
	}
}

interface ReservationsService {

	Page<Reservation> findAll(String name, String lang, Pageable pageable);

	Optional<Reservation> findOne(Long id);

	Reservation create(Reservation reservation);

	Reservation update(Long id, Reservation reservation);

	void delete(Long id);
}

@Transactional
class ReservationsServiceImpl implements ReservationsService {

	private final ReservationsRepository reservations;

	ReservationsServiceImpl(ReservationsRepository reservations) {
		this.reservations = reservations;
	}

	@Transactional(propagation = SUPPORTS, readOnly = true)
	public Page<Reservation> findAll(String name, String lang, Pageable pageable) {
		return reservations.findAll(new BooleanBuilder()
				.and(withName(name))
				.and(withLang(lang)),
				pageable);
	}

	@Transactional(propagation = SUPPORTS, readOnly = true)
	public Optional<Reservation> findOne(Long id) {
		return Optional.ofNullable(reservations.findOne(id));
	}

	public Reservation create(Reservation reservation) {
		validateNotExists(reservation.getName());
		reservations.save(reservation);
		return reservation;
	}

	private void validateNotExists(String name) {
		reservations.findByName(name)
			.ifPresent(existing -> {
				throw new ReservationAlreadyExists(existing.getName());
			});
	}

	public Reservation update(Long id, Reservation reservation) {
		return findOne(id)
			.map(existing -> {
				validateNotExists(reservation.getName());
				existing.setName(reservation.getName());
				existing.setLang(reservation.getLang());
				reservations.save(existing);
				return existing;
			})
			.orElseThrow(() -> new ReservationNotFound(id));
	}

	public void delete(Long id) {
		reservations.delete(id);
	}
}

class ReservationNotFound extends RuntimeException {
	ReservationNotFound(Long id) {
		super("Reservation for id '" + id + "' not found!");
	}
}

@ResponseStatus(CONFLICT)
class ReservationAlreadyExists extends RuntimeException {
	ReservationAlreadyExists(String name) {
		super("Reservation for name '" + name + "' already exists!");
	}
}

@Configuration
@EnableJpaRepositories

class RepositoryConfig {
}

@Component
class ReservationsInitializer implements ApplicationRunner {

	@Autowired ReservationsRepository reservations;

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws Exception {
		Stream.of(
			"Tomek:PLSQL", "Tomasz:PLSQL", "Stanisław:PLSQL",
			"Grzegorz:C++", "Rafał:C++", "Andrzej:C++", "Tom:C++",
			"Marek:Java", "Artur:OracleForms", "Jędrek:OracleForms")
			.map(entry -> entry.split(":"))
			.map(entry -> new Reservation(entry[0], entry[1]))
			.filter(r -> !reservations.findByName(r.getName()).isPresent())
			.forEach(r-> reservations.save(r));

	}
}

